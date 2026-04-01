-- ── Seed initial routing rules (v1 topic naming convention) ─────────────
-- Format: banking/v1/{entity}/{action}

INSERT INTO routing_rules (event_type, topic, owner_service, direction, description, active) VALUES
('ACCOUNT_CREATED',   'banking/v1/account/created',   'account-service', 'PUBLISH', 'Published when a new bank account is opened',               true),
('ACCOUNT_UPDATED',   'banking/v1/account/updated',   'account-service', 'PUBLISH', 'Published when account details are modified',               true),
('ACCOUNT_CLOSED',    'banking/v1/account/closed',    'account-service', 'PUBLISH', 'Published when a bank account is closed',                   true),
('PAYMENT_INITIATED', 'banking/v1/payment/initiated', 'payment-service', 'PUBLISH', 'Published when a payment request is received by payment-service', true),
('PAYMENT_COMPLETED', 'banking/v1/payment/completed', 'account-service', 'PUBLISH', 'Published by account-service after successful debit/credit', true),
('PAYMENT_FAILED',    'banking/v1/payment/failed',    'account-service', 'PUBLISH', 'Published by account-service when payment cannot be processed', true),
('ROUTING_UPDATED',   'banking/v1/routing/updated',   'routing-service', 'PUBLISH', 'Published when any routing rule is changed — triggers cache invalidation', true);
