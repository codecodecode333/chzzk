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

/**
 * A scriptable payment provider for API tests. By default it approves every confirm exactly as asked ({@code DONE},
 * {@code totalAmount} = the confirmed amount) and remembers the payment, so lookups find it afterwards. Script the
 * next confirm with {@link #nextConfirm}, and inspect what was called with {@link #confirms()}.
 *
 * <pre>{@code
 * payments.nextConfirm(r -> r.withTotalAmount(1_000));            // provider reports a different amount
 * payments.nextConfirm(r -> { throw new PaymentRejectedException("REJECT_CARD_PAYMENT", "한도초과"); });
 * payments.nextConfirm(r -> { throw new PaymentUnavailableException("timeout"); });
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
    }

    @Override
    public PaymentResult confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        ConfirmScript script;
        synchronized (this) {
            confirms.add(new ConfirmCall(paymentKey, orderId, amount, idempotencyKey));
            script = scripts.isEmpty() ? r -> r : scripts.removeFirst();
        }
        PaymentResult approval = new PaymentResult(PaymentStatus.DONE, amount, orderId, paymentKey, APPROVED_AT);
        PaymentResult answer = script.answer(approval);
        byPaymentKey.put(paymentKey, answer);
        return answer;
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
