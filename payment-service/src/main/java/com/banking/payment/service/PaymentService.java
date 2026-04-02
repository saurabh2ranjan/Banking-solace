package com.banking.payment.service;

import com.banking.payment.dto.*;
import com.banking.events.PaymentEvents.*;
import com.banking.payment.model.Payment;
import com.banking.payment.model.Payment.PaymentStatus;
import com.banking.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventPublisher eventPublisher;

    /**
     * Creates a payment record and publishes PaymentInitiatedEvent to Solace.
     * Account Service will pick this up, validate, and process.
     */
    @Transactional
    public PaymentResponse initiatePayment(PaymentRequest request) {
        String paymentRef = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Payment payment = Payment.builder()
                .paymentReference(paymentRef)
                .fromAccountId(request.getFromAccountId())
                .toAccountId(request.getToAccountId())
                .amount(request.getAmount())
                .description(request.getDescription())
                .status(PaymentStatus.PROCESSING)
                .build();

        Payment saved = paymentRepository.save(payment);
        log.info("Payment created: id={}, ref={}", saved.getId(), saved.getPaymentReference());

        // ── Publish to Solace → Account Service will process ──────
        eventPublisher.publishPaymentInitiated(PaymentInitiatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .paymentId(saved.getId())
                .fromAccountId(saved.getFromAccountId())
                .toAccountId(saved.getToAccountId())
                .amount(saved.getAmount())
                .description(saved.getDescription())
                .timestamp(LocalDateTime.now())
                .source("payment-service")
                .build());

        return toResponse(saved);
    }

    /**
     * Called when Account Service publishes PaymentCompleted event via Solace.
     */
    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("▸ Payment completed callback: paymentId={}", event.getPaymentId());
        paymentRepository.findById(event.getPaymentId()).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setCompletedAt(LocalDateTime.now());
            paymentRepository.save(payment);
            log.info("Payment status updated to COMPLETED: ref={}", payment.getPaymentReference());
        });
    }

    /**
     * Called when Account Service publishes PaymentFailed event via Solace.
     */
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.warn("▸ Payment failed callback: paymentId={}, reason={}", event.getPaymentId(), event.getReason());
        paymentRepository.findById(event.getPaymentId()).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(event.getReason());
            payment.setCompletedAt(LocalDateTime.now());
            paymentRepository.save(payment);
            log.warn("Payment status updated to FAILED: ref={}", payment.getPaymentReference());
        });
    }

    public PaymentResponse getPayment(String id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + id));
        return toResponse(payment);
    }

    public List<PaymentResponse> getAllPayments() {
        return paymentRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<PaymentResponse> getPaymentsByAccount(String accountId) {
        return paymentRepository.findByFromAccountIdOrToAccountId(accountId, accountId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private PaymentResponse toResponse(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId())
                .paymentReference(payment.getPaymentReference())
                .fromAccountId(payment.getFromAccountId())
                .toAccountId(payment.getToAccountId())
                .amount(payment.getAmount())
                .description(payment.getDescription())
                .status(payment.getStatus().name())
                .failureReason(payment.getFailureReason())
                .createdAt(payment.getCreatedAt())
                .completedAt(payment.getCompletedAt())
                .build();
    }
}
