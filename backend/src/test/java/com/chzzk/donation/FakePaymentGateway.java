package com.chzzk.donation;

import com.chzzk.donation.payment.PaymentGateway;
import com.chzzk.donation.payment.PaymentRejectedException;
import com.chzzk.donation.payment.PaymentResult;
import com.chzzk.donation.payment.PaymentStatus;
import com.chzzk.donation.payment.PaymentUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A scriptable payment provider for API tests. By default it approves every confirm exactly as asked ({@code DONE},
 * {@code totalAmount} = the confirmed amount) and remembers the payment, so lookups find it afterwards. Script the
 * next confirm with {@link #nextConfirm}, and inspect what was called with {@link #confirms()}.
 *
 * <pre>{@code
 * payments.nextConfirm(r -> r.withTotalAmount(1_000));            // provider reports a different amount
 * payments.nextConfirm(r -> { throw new PaymentRejectedException("REJECT_CARD_PAYMENT", "한도초과"); });
 * payments.nextConfirm(r -> { throw new PaymentUnavailableException("timeout"); });
 * payments.approved("tgen_x", orderId, 5_000);                   // Toss already has this payment approved
 * payments.race(2);                                                // the next 2 confirms meet at Toss at once
 * }</pre>
 */
public class FakePaymentGateway implements PaymentGateway {

    /** Approval time of every payment this fake approves. */
    public static final Instant APPROVED_AT = Instant.parse("2026-11-15T10:10:00Z");

    /** Turns the default approval into the scripted answer (or throws). */
    @FunctionalInterface
    public interface ConfirmScript {
        PaymentResult answer(PaymentResult approval);
    }

    /** One confirm call as the gateway received it. */
    public record ConfirmCall(String paymentKey, String orderId, long amount, String idempotencyKey) {}

    private final List<ConfirmScript> scripts = new ArrayList<>();
    private final List<ConfirmCall> confirms = new ArrayList<>();
    private final Map<String, PaymentResult> byPaymentKey = new ConcurrentHashMap<>();
    private CountDownLatch race;
    private int racers;

    /** The answer to the next confirm call only; later calls fall back to approving. */
    public synchronized FakePaymentGateway nextConfirm(ConfirmScript script) {
        scripts.add(script);
        return this;
    }

    public synchronized List<ConfirmCall> confirms() {
        return List.copyOf(confirms);
    }

    public synchronized void reset() {
        scripts.clear();
        confirms.clear();
        byPaymentKey.clear();
        race = null;
        racers = 0;
    }

    /** Toss already holds this payment as approved ({@code DONE}), so lookups find it. */
    public void approved(String paymentKey, String orderId, long amount) {
        byPaymentKey.put(paymentKey, new PaymentResult(PaymentStatus.DONE, amount, orderId, paymentKey, APPROVED_AT));
    }

    /**
     * The next {@code parties} confirms of one payment meet here: none is answered until all have arrived. Then, like
     * Toss confirming one payment twice, the first to arrive approves it and every later one is refused with
     * {@code ALREADY_PROCESSED_PAYMENT}. The refusals are answered first and the approval a moment later, the worst
     * order for the caller: a refused confirm is settled while the approved one is still on its way back.
     */
    public synchronized FakePaymentGateway race(int parties) {
        race = new CountDownLatch(parties);
        racers = parties;
        return this;
    }

    @Override
    public PaymentResult confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        ConfirmScript script;
        CountDownLatch meeting = null;
        boolean firstRacer = false;
        synchronized (this) {
            confirms.add(new ConfirmCall(paymentKey, orderId, amount, idempotencyKey));
            if (racers > 0) {
                meeting = race;
                firstRacer = race.getCount() == racers;
                // Toss approves the first to arrive at once, before any later racer can be refused and look it up.
                if (firstRacer) {
                    byPaymentKey.put(paymentKey,
                            new PaymentResult(PaymentStatus.DONE, amount, orderId, paymentKey, APPROVED_AT));
                }
                race.countDown();
                if (race.getCount() == 0) racers = 0;
            }
            script = scripts.isEmpty() || meeting != null ? r -> r : scripts.removeFirst();
        }
        PaymentResult approval = new PaymentResult(PaymentStatus.DONE, amount, orderId, paymentKey, APPROVED_AT);
        if (meeting != null) return race(meeting, firstRacer, approval);
        PaymentResult answer = script.answer(approval);
        byPaymentKey.put(paymentKey, answer);
        return answer;
    }

    private PaymentResult race(CountDownLatch meeting, boolean first, PaymentResult approval) {
        try {
            if (!meeting.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the other racers never came");
            if (!first) throw new PaymentRejectedException("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.");
            // approved at Toss on arrival (see confirm); only the answer is slow
            Thread.sleep(500);
            return approval;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<PaymentResult> lookup(String paymentKey) {
        return Optional.ofNullable(byPaymentKey.get(paymentKey));
    }

    @Override
    public Optional<PaymentResult> lookupByOrderId(String orderId) {
        return byPaymentKey.values().stream().filter(p -> p.orderId().equals(orderId)).findFirst();
    }

    /** Shorthand scripts. */
    public static ConfirmScript rejects(String code, String message) {
        return r -> {
            throw new PaymentRejectedException(code, message);
        };
    }

    public static ConfirmScript unreachable() {
        return r -> {
            throw new PaymentUnavailableException("결제사에 연결하지 못했습니다.");
        };
    }
}
