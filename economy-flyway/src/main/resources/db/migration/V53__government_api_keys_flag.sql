-- PAR-521: registration only; operators enable after compatible deployments.
INSERT INTO feature_flags (name, description)
VALUES ('treasury.government_api_keys', 'Enable single-account government API key issuance, authentication and webhook delivery')
ON DUPLICATE KEY UPDATE name = VALUES(name);
