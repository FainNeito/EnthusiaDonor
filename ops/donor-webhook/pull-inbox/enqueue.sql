-- Invoke only AFTER authenticating the complete raw body and checking admission.
-- Bind: source, SHA-256(raw body), raw body bytes, original signature.
INSERT INTO inbox(source,id,body,signature)
VALUES(?1,?2,?3,?4)
ON CONFLICT(source,id) DO NOTHING;
