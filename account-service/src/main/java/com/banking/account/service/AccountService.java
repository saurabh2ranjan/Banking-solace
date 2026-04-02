package com.banking.account.service;

import com.banking.account.dto.*;
import com.banking.events.AccountEvents.*;
import com.banking.events.PaymentEvents.*;
import com.banking.account.model.Account;
import com.banking.account.model.Account.AccountStatus;
import com.banking.account.model.Account.AccountType;
import com.banking.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountEventPublisher eventPublisher;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        String accountNumber = generateAccountNumber();

        Account account = Account.builder()
                .accountNumber(accountNumber)
                .customerName(request.getCustomerName())
                .email(request.getEmail())
                .accountType(AccountType.valueOf(request.getAccountType().toUpperCase()))
                .balance(request.getInitialBalance())
                .status(AccountStatus.ACTIVE)
                .build();

        Account saved = accountRepository.save(account);
        log.info("Account created: id={}, number={}", saved.getId(), saved.getAccountNumber());

        // ── Publish event to Solace ────────────────────────────────
        eventPublisher.publishAccountCreated(AccountCreatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .accountId(saved.getId())
                .accountNumber(saved.getAccountNumber())
                .customerName(saved.getCustomerName())
                .email(saved.getEmail())
                .accountType(saved.getAccountType().name())
                .balance(saved.getBalance())
                .timestamp(LocalDateTime.now())
                .source("account-service")
                .build());

        return toResponse(saved);
    }

    public AccountResponse getAccount(String id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Account not found: " + id));
        return toResponse(account);
    }

    public AccountResponse getAccountByNumber(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountNumber));
        return toResponse(account);
    }

    public List<AccountResponse> getAllAccounts() {
        return accountRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Called when a PaymentInitiatedEvent is received from Solace.
     * Validates accounts, checks balance, performs debit/credit, then
     * publishes either PaymentCompleted or PaymentFailed.
     */
    @Transactional
    public void processPayment(PaymentInitiatedEvent event) {
        log.info("Processing payment: paymentId={}, from={}, to={}, amount={}",
                event.getPaymentId(), event.getFromAccountId(),
                event.getToAccountId(), event.getAmount());

        try {
            Account fromAccount = accountRepository.findByAccountNumber(event.getFromAccountId())
                    .or(() -> accountRepository.findById(event.getFromAccountId()))
                    .orElseThrow(() -> new RuntimeException(
                            "Source account not found: " + event.getFromAccountId()));

            Account toAccount = accountRepository.findByAccountNumber(event.getToAccountId())
                    .or(() -> accountRepository.findById(event.getToAccountId()))
                    .orElseThrow(() -> new RuntimeException(
                            "Destination account not found: " + event.getToAccountId()));

            // Validate accounts are active
            if (fromAccount.getStatus() != AccountStatus.ACTIVE) {
                throw new RuntimeException("Source account is not active");
            }
            if (toAccount.getStatus() != AccountStatus.ACTIVE) {
                throw new RuntimeException("Destination account is not active");
            }

            // Check sufficient balance
            if (fromAccount.getBalance().compareTo(event.getAmount()) < 0) {
                throw new RuntimeException("Insufficient balance. Available: "
                        + fromAccount.getBalance() + ", Required: " + event.getAmount());
            }

            // Perform debit and credit
            fromAccount.setBalance(fromAccount.getBalance().subtract(event.getAmount()));
            toAccount.setBalance(toAccount.getBalance().add(event.getAmount()));

            accountRepository.save(fromAccount);
            accountRepository.save(toAccount);

            log.info("Payment processed successfully: paymentId={}", event.getPaymentId());

            // Publish success event
            eventPublisher.publishPaymentCompleted(PaymentCompletedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .paymentId(event.getPaymentId())
                    .fromAccountId(fromAccount.getAccountNumber())
                    .toAccountId(toAccount.getAccountNumber())
                    .amount(event.getAmount())
                    .fromNewBalance(fromAccount.getBalance())
                    .toNewBalance(toAccount.getBalance())
                    .timestamp(LocalDateTime.now())
                    .source("account-service")
                    .build());

        } catch (Exception ex) {
            log.error("Payment failed: paymentId={}, reason={}",
                    event.getPaymentId(), ex.getMessage());

            eventPublisher.publishPaymentFailed(PaymentFailedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .paymentId(event.getPaymentId())
                    .fromAccountId(event.getFromAccountId())
                    .toAccountId(event.getToAccountId())
                    .amount(event.getAmount())
                    .reason(ex.getMessage())
                    .timestamp(LocalDateTime.now())
                    .source("account-service")
                    .build());
        }
    }

    @Transactional
    public AccountResponse closeAccount(String accountId, String reason) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new RuntimeException("Account is already closed: " + account.getAccountNumber());
        }

        account.setStatus(AccountStatus.CLOSED);
        Account saved = accountRepository.save(account);
        log.info("Account closed: id={}, number={}", saved.getId(), saved.getAccountNumber());

        eventPublisher.publishAccountClosed(AccountClosedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .accountId(saved.getId())
                .accountNumber(saved.getAccountNumber())
                .customerName(saved.getCustomerName())
                .email(saved.getEmail())
                .reason(reason != null ? reason : "No reason provided")
                .finalBalance(saved.getBalance())
                .timestamp(LocalDateTime.now())
                .source("account-service")
                .build());

        return toResponse(saved);
    }

    @Transactional
    public AccountResponse deposit(String accountId, BigDecimal amount) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        BigDecimal oldBalance = account.getBalance();
        account.setBalance(account.getBalance().add(amount));
        Account saved = accountRepository.save(account);

        eventPublisher.publishAccountUpdated(AccountUpdatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .accountId(saved.getId())
                .accountNumber(saved.getAccountNumber())
                .field("balance")
                .oldValue(oldBalance.toString())
                .newValue(saved.getBalance().toString())
                .timestamp(LocalDateTime.now())
                .source("account-service")
                .build());

        return toResponse(saved);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private String generateAccountNumber() {
        long number = 1000000000L + ThreadLocalRandom.current().nextLong(9000000000L);
        return "ACC-" + number;
    }

    private AccountResponse toResponse(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .customerName(account.getCustomerName())
                .email(account.getEmail())
                .accountType(account.getAccountType().name())
                .balance(account.getBalance())
                .status(account.getStatus().name())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }
}
