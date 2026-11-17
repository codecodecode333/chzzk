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
 * Hold hold = payments.holdNextConfirm();                          // Toss approves the next confirm, answers on release
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
    /** Counted down by each refused racer's lookup; the approved racer answers once all have looked it up. */
    private CountDownLatch refusedRacersLookedUp;
    private Hold hold;
    private volatile boolean lookupsUnreachable;

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
        refusedRacersLookedUp = null;
        hold = null;
        lookupsUnreachable = false;
    }

    /** Toss already holds this payment as approved ({@code DONE}), so lookups find it. */
    public void approved(String paymentKey, String orderId, long amount) {
        byPaymentKey.put(paymentKey, new PaymentResult(PaymentStatus.DONE, amount, orderId, paymentKey, APPROVED_AT));
    }

    /** Toss holds this payment in the given status (e.g. {@code IN_PROGRESS}: authenticated, not approved). */
    public void holds(String paymentKey, String orderId, long amount, PaymentStatus status) {
        byPaymentKey.put(paymentKey, new PaymentResult(status, amount, orderId, paymentKey,
                status == PaymentStatus.DONE ? APPROVED_AT : null));
    }

    /** While set, every lookup fails as if Toss can't be reached. */
    public void lookupsUnreachable(boolean unreachable) {
        lookupsUnreachable = unreachable;
    }

    /**
     * The next {@code parties} confirms of one payment meet here: none is answered until all have arrived. Then, like
     * Toss confirming one payment twice, the first to arrive approves it and every later one is refused with
     * {@code ALREADY_PROCESSED_PAYMENT}. The refusals are answered first, and the approval only once every refused
     * racer has looked the payment up, the worst order for the caller: a refused confirm is being settled while the
     * approved one is still on its way back.
     */
    public synchronized FakePaymentGateway race(int parties) {
        race = new CountDownLatch(parties);
        racers = parties;
        refusedRacersLookedUp = new CountDownLatch(parties - 1);
        return this;
    }

    /**
     * Toss approves the next confirm as soon as it arrives (so lookups find it {@code DONE}) but holds its answer until
     * {@link Hold#release()}: the moment a confirm is "at Toss", for racing it against something else.
     */
    public synchronized Hold holdNextConfirm() {
        hold = new Hold();
        return hold;
    }

    /** A confirm held at Toss: wait for it to arrive, then let its answer go. */
    public static final class Hold {
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        /** Blocks until the held confirm has reached Toss (and Toss has approved it). */
        public void awaitArrival() {
            await(arrived, "the held confirm never arrived");
        }

        public void release() {
            released.countDown();
        }
    }

    private static void await(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(timeoutMessage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public PaymentResult confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        ConfirmScript script;
        CountDownLatch meeting = null;
        boolean firstRacer = false;
        Hold held;
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
            held = hold;
            hold = null;
        }
        PaymentResult approval = new PaymentResult(PaymentStatus.DONE, amount, orderId, paymentKey, APPROVED_AT);
        if (meeting != null) return race(meeting, firstRacer, approval);
        if (held != null) {
            byPaymentKey.put(paymentKey, approval);
            held.arrived.countDown();
            await(held.released, "the held confirm was never released");
        }
        PaymentResult answer = script.answer(approval);
        byPaymentKey.put(paymentKey, answer);
        return answer;
    }

    private PaymentResult race(CountDownLatch meeting, boolean first, PaymentResult approval) {
        await(meeting, "the other racers never came");
        if (!first) throw new PaymentRejectedException("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제 입니다.");
        // approved at Toss on arrival (see confirm); the answer comes back only after the refused racers looked it up
        CountDownLatch lookedUp;
        synchronized (this) {
            lookedUp = refusedRacersLookedUp;
        }
        if (lookedUp != null) await(lookedUp, "the refused racers never looked the payment up");
        return approval;
    }

    @Override
    public Optional<PaymentResult> lookup(String paymentKey) {
        if (lookupsUnreachable) throw new PaymentUnavailableException("결제사에 연결하지 못했습니다.");
        Optional<PaymentResult> found = Optional.ofNullable(byPaymentKey.get(paymentKey));
        CountDownLatch lookedUp;
        synchronized (this) {
            lookedUp = refusedRacersLookedUp;
        }
        if (lookedUp != null) lookedUp.countDown();
        return found;
    }

    @Override
    public Optional<PaymentResult> lookupByOrderId(String orderId) {
        if (lookupsUnreachable) throw new PaymentUnavailableException("결제사에 연결하지 못했습니다.");
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
