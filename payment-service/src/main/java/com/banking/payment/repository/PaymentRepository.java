package com.banking.payment.repository;

import com.banking.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {
    Optional<Payment> findByPaymentReference(String paymentReference);
    List<Payment> findByFromAccountIdOrToAccountId(String fromAccountId, String toAccountId);
    List<Payment> findByStatus(Payment.PaymentStatus status);
}
