package com.chzzk.donation.domain;

import com.chzzk.common.auth.CurrentMember;
import com.chzzk.common.auth.MemberRef;
import com.chzzk.common.error.ConflictException;
import com.chzzk.common.error.ForbiddenException;
import com.chzzk.common.text.Text;
import com.chzzk.donation.payment.PaymentGateway;
import com.chzzk.donation.payment.PaymentRejectedException;
import com.chzzk.donation.payment.PaymentResult;
import com.chzzk.donation.payment.PaymentStatus;
import com.chzzk.donation.payment.PaymentUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후원 (ADR-0004): the one place a 후원 order is created and changes state.
 *
 * <ul>
 *   <li>{@link #start} creates a PENDING order before payment, fixing the amount and the recipient (the 방송's
 *       소유자).</li>
 *   <li>{@link #confirm} is the success redirect: the server approves the payment with the provider itself, and the
 *       order is CONFIRMED only when the provider reports it {@code DONE} for exactly our order and amount.</li>
 *   <li>{@link #fail} records a payment the 후원자 abandoned or the payment window refused.</li>
 * </ul>
 *
 * Transitions are conditional updates ({@link DonationRepository}), never read-modify-write, and the provider is
 * called outside any database transaction. Only the order's 후원자 may confirm or fail it.
 *
 * <p>Idempotency: one approval credits one 후원, however often it arrives. A CONFIRMED order is answered as it is
 * without asking the provider; of concurrent callers exactly one wins the transition and the others answer the state it
 * left; the provider confirm carries {@code Idempotency-Key = orderId}, and its {@code ALREADY_PROCESSED_PAYMENT} is
 * resolved by looking the payment up. Whatever the provider says goes through {@link #settle}, the one place that
 * verifies a payment against its order and moves the order on.
 */
@Service
public class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);

    public static final int MIN_AMOUNT = 1_000;
    public static final int MAX_AMOUNT = 1_000_000;
    public static final int AMOUNT_STEP = 1_000;
    public static final int MESSAGE_MAX_CHARS = 100;

    static final String AMOUNT_RULE = "후원 금액은 1,000원부터 1,000,000원까지 1,000원 단위로 입력해 주세요.";
    static final String FAILED_ORDER = "실패한 후원입니다. 다시 후원해 주세요.";
    static final String AMOUNT_MISMATCH = "결제 금액이 후원 금액과 다릅니다.";
    /** Toss's answer to confirming a payment it already approved. */
    static final String ALREADY_PROCESSED_PAYMENT = "ALREADY_PROCESSED_PAYMENT";

    private final DonationRepository repository;
    private final PaymentGateway gateway;
    private final CurrentMember currentMember;
    private final Clock clock;

    DonationService(DonationRepository repository, PaymentGateway gateway, CurrentMember currentMember, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.currentMember = currentMember;
        this.clock = clock;
    }

    /**
     * The current 회원 orders a 후원 to a 방송 중 방송 they don't own. Visitor {@code 401}, unknown 방송 {@code 404},
     * own 방송 {@code 403}, 종료 방송 {@code 409}, bad amount or message {@code 400}.
     */
    @Transactional
    public DonationOrder start(long broadcastId, Integer amount, String message) {
        MemberRef donor = currentMember.require();
        DonationBroadcast broadcast = repository.findBroadcast(broadcastId)
                .orElseThrow(() -> new DonationNotFoundException(broadcastId + "번 방송을 찾을 수 없습니다."));
        MemberRef recipient = broadcast.getOwner().toRef();
        if (recipient.id() == donor.id()) throw new ForbiddenException("내 방송에는 후원할 수 없습니다.");
        if (!broadcast.isLive()) throw new ConflictException("종료된 방송에는 후원할 수 없습니다.");
        int validAmount = validAmount(amount);
        String validMessage = validMessage(message);

        Donation order = repository.save(new Donation(UUID.randomUUID(), broadcastId, donor.id(), recipient.id(),
                validAmount, validMessage, now()));
        return new DonationOrder(order.getOrderId(), broadcastId, validAmount, recipient);
    }

    /**
     * The 후원자 back from the payment window with Toss's {@code paymentKey}, {@code orderId} and {@code amount}.
     * A client amount other than the order's fails the order without asking the provider. Otherwise the provider
     * approves the order's own amount, and the order is CONFIRMED only if it reports {@code DONE} with that
     * {@code totalAmount} and {@code orderId}; anything else (or a rejection) fails it ({@code 400}). An unreachable
     * provider leaves it PENDING ({@code 503}). A CONFIRMED order is returned as it is; a FAILED one is {@code 409}.
     * Repeating or racing a confirm never credits twice: see the class comment.
     */
    public DonationView confirm(String paymentKey, String orderId, Long amount) {
        MemberRef caller = currentMember.require();
        if (paymentKey == null || paymentKey.isBlank()) {
            throw new InvalidDonationException("paymentKey", "결제 키가 없습니다.");
        }
        if (amount == null) throw new InvalidDonationException("amount", "결제 금액이 없습니다.");
        Donation order = ownOrder(caller, orderId, "내 후원만 확정할 수 있습니다.");
        switch (order.getStatus()) {
            case CONFIRMED -> {
                return view(order);
            }
            case FAILED -> throw new ConflictException(FAILED_ORDER);
            case PENDING, EXPIRED -> { }
        }
        if (amount != order.getAmount()) {
            return failed(order, PaymentNotConfirmedException.AMOUNT_MISMATCH, AMOUNT_MISMATCH);
        }

        String id = order.getOrderId().toString();
        PaymentResult payment;
        try {
            // A PaymentUnavailableException passes through (503): the order stays as it is for a retry or lookup.
            payment = gateway.confirm(paymentKey, id, order.getAmount(), id);
        } catch (PaymentRejectedException e) {
            if (!ALREADY_PROCESSED_PAYMENT.equals(e.code())) return failed(order, e.code(), e.getMessage());
            // Toss approved this payment before (a concurrent or earlier confirm): what it holds now is the answer.
            payment = gateway.lookup(paymentKey).orElseThrow(() -> new PaymentUnavailableException(
                    "결제 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."));
        }
        return settle(order, payment);
    }

    /** The 후원자 reports that the payment failed or was cancelled: a PENDING order becomes FAILED. */
    public void fail(String orderId, String code, String message) {
        MemberRef caller = currentMember.require();
        Donation order = ownOrder(caller, orderId, "내 후원만 취소할 수 있습니다.");
        String failureCode = code == null || code.isBlank() ? "PAYMENT_FAILED" : truncate(code.strip(), 100);
        if (repository.markFailed(order.getOrderId(), failureCode) == 1) {
            log.info("후원 order {} failed by the payment window: {} {}", orderId, failureCode, message);
        }
    }

    /**
     * Applies the provider's report of a payment to the order: CONFIRMED only for {@code DONE} with exactly our amount
     * and order id, FAILED otherwise. The single place an order becomes CONFIRMED, so every path (the success
     * redirect, and a webhook reconcile that looks the payment up) applies the same rules and the same conditional
     * transition. Of concurrent callers one wins; the others answer the state it left: the 후원 if CONFIRMED, else
     * {@code 409} or the verification failure.
     */
    private DonationView settle(Donation order, PaymentResult payment) {
        if (payment.status() != PaymentStatus.DONE) {
            return failed(order, "PAYMENT_" + payment.status().name(), "결제가 완료되지 않았습니다.");
        }
        if (payment.totalAmount() != order.getAmount()) {
            return failed(order, PaymentNotConfirmedException.AMOUNT_MISMATCH, AMOUNT_MISMATCH);
        }
        if (!order.getOrderId().toString().equals(payment.orderId())) {
            return failed(order, PaymentNotConfirmedException.PAYMENT_MISMATCH, "결제 정보가 후원 주문과 다릅니다.");
        }
        Instant confirmedAt = payment.approvedAt() != null ? payment.approvedAt() : now();
        repository.markConfirmed(order.getOrderId(), payment.paymentKey(),
                confirmedAt.truncatedTo(ChronoUnit.MICROS));
        Donation now = reload(order);
        if (now.getStatus() != DonationStatus.CONFIRMED) throw new ConflictException(FAILED_ORDER);
        return view(now);
    }

    /**
     * Fails the order if it is still open and throws why. If another caller confirmed it first, that 후원 stands
     * and is returned: a late refusal or mismatch never undoes a confirmation.
     */
    private DonationView failed(Donation order, String code, String message) {
        if (repository.markFailed(order.getOrderId(), truncate(code, 100)) == 0) {
            Donation now = reload(order);
            if (now.getStatus() == DonationStatus.CONFIRMED) return view(now);
        }
        log.info("후원 order {} not confirmed: {} {}", order.getOrderId(), code, message);
        throw new PaymentNotConfirmedException(code, message);
    }

    private Donation ownOrder(MemberRef caller, String orderId, String forbiddenMessage) {
        Donation order = parse(orderId).flatMap(repository::findByOrderId)
                .orElseThrow(() -> new DonationNotFoundException("후원 주문을 찾을 수 없습니다."));
        if (order.getDonorId() != caller.id()) throw new ForbiddenException(forbiddenMessage);
        return order;
    }

    private Donation reload(Donation order) {
        return repository.findByOrderId(order.getOrderId()).orElseThrow();
    }

    private DonationView view(Donation order) {
        return DonationView.of(order, member(order.getDonorId()), member(order.getRecipientId()));
    }

    private MemberRef member(long id) {
        return repository.findMember(id).orElseThrow().toRef();
    }

    private static Optional<UUID> parse(String orderId) {
        if (orderId == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(orderId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static int validAmount(Integer amount) {
        if (amount == null) throw new InvalidDonationException("amount", "후원 금액을 입력해 주세요.");
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT || amount % AMOUNT_STEP != 0) {
            throw new InvalidDonationException("amount", AMOUNT_RULE);
        }
        return amount;
    }

    /** Trimmed like the form does; blank means no message. */
    private static String validMessage(String message) {
        String trimmed = message == null ? "" : Text.trimWhitespace(message);
        if (trimmed.isEmpty()) return null;
        if (Text.charCount(trimmed) > MESSAGE_MAX_CHARS) {
            throw new InvalidDonationException("message", "후원 메시지는 " + MESSAGE_MAX_CHARS + "자 이하로 입력해 주세요.");
        }
        return trimmed;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS); // match TIMESTAMPTZ precision
    }
}
