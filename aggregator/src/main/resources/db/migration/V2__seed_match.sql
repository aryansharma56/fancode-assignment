-- Seed data: the one match this assignment ingests, and the provider id mapping.
INSERT INTO matches (id, title) VALUES ('match-1001', 'Demo XI vs Sample XI');

INSERT INTO provider_match_map (provider, provider_match_id, match_id) VALUES
    ('provider-a', 'a-match-1001', 'match-1001'),
    ('provider-b', 'b-match-1001', 'match-1001');
