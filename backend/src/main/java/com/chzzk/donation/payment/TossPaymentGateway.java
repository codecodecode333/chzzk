package com.chzzk.donation.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link PaymentGateway} over the Toss Payments core API ({@code https://api.tosspayments.com/v1}), authenticated
 * with HTTP Basic: the secret key as user name and an empty password. The secret key comes from
 * {@code donation.toss.secret-key} ({@code TOSS_SECRET_KEY}, by default the public docs 결제창 test key) and stays on
 * the server. With a {@code test_} key every payment is simulated: no money moves.
 *
 * <p>Confirm sends the {@code Idempotency-Key} header, so a retried confirm of one order returns Toss's first answer
 * instead of approving again.
 */
@Component
class TossPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentGateway.class);

    /** Toss answers these when the outcome isn't settled yet: retry later rather than fail the order. */
    private static final Set<String> RETRYABLE_CODES = Set.of("PROVIDER_ERROR", "IDEMPOTENT_REQUEST_PROCESSING",
            "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "FAILED_INTERNAL_SYSTEM_PROCESSING", "UNKNOWN_PAYMENT_ERROR",
            // our key is wrong: a server misconfiguration, not the buyer's fault
            "UNAUTHORIZED_KEY", "INVALID_API_KEY");

    private final RestClient api;
    private final ObjectMapper json;

    TossPaymentGateway(RestClient.Builder builder, ObjectMapper json,
                       @Value("${donation.toss.api-url}") String apiUrl,
                       @Value("${donation.toss.secret-key}") String secretKey) {
        // Toss advises a generous read timeout for confirm (card networks can be slow).
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requests.setReadTimeout(Duration.ofSeconds(60));
        String credentials = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.api = builder.clone()
                .baseUrl(apiUrl)
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .build();
        this.json = json;
    }

    @Override
    public PaymentResult confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        try {
            Payment payment = api.post()
                    .uri("/v1/payments/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                    .retrieve()
                    .body(Payment.class);
            return toResult(payment);
        } catch (RestClientResponseException e) {
            TossError error = errorOf(e);
            if (e.getStatusCode().is5xxServerError() || RETRYABLE_CODES.contains(error.code())) {
                log.warn("Toss confirm for order {} not settled: {} {}", orderId, error.code(), error.message());
                throw new PaymentUnavailableException("결제사 응답을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
            }
            throw new PaymentRejectedException(error.code(), error.message());
        } catch (RestClientException e) {
            log.warn("Toss confirm for order {} failed: {}", orderId, e.toString());
            throw new PaymentUnavailableException("결제사에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }

    @Override
    public Optional<PaymentResult> lookup(String paymentKey) {
        return get("/v1/payments/{paymentKey}", paymentKey);
    }

    @Override
    public Optional<PaymentResult> lookupByOrderId(String orderId) {
        return get("/v1/payments/orders/{orderId}", orderId);
    }

    private Optional<PaymentResult> get(String uri, String variable) {
        try {
            return Optional.ofNullable(api.get().uri(uri, variable).retrieve().body(Payment.class)).map(this::toResult);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
            TossError error = errorOf(e);
            log.warn("Toss lookup {} failed: {} {}", variable, error.code(), error.message());
            throw new PaymentUnavailableException("결제사 응답을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        } catch (RestClientException e) {
            throw new PaymentUnavailableException("결제사에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }

    private PaymentResult toResult(Payment payment) {
        if (payment == null) throw new PaymentUnavailableException("결제사 응답이 비어 있습니다.");
        return new PaymentResult(PaymentStatus.of(payment.status()), payment.totalAmount(), payment.orderId(),
                payment.paymentKey(), payment.approvedAt() == null ? null : payment.approvedAt().toInstant());
    }

    private TossError errorOf(RestClientResponseException e) {
        try {
            TossError error = json.readValue(e.getResponseBodyAsByteArray(), TossError.class);
            if (error.code() != null) {
                return new TossError(error.code(), error.message() != null ? error.message() : "결제에 실패했습니다.");
            }
        } catch (Exception ignored) {
            // not Toss's error JSON (e.g. a proxy's HTML page)
        }
        return new TossError("HTTP_" + e.getStatusCode().value(), "결제에 실패했습니다.");
    }

    /** The fields of Toss's Payment object this module uses. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payment(String paymentKey, String orderId, String status, long totalAmount, OffsetDateTime approvedAt) {}

    /** Toss's error body. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TossError(String code, String message) {}
}
