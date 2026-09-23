-- docs/DESIGN-PROD.md §7 and §25 V2: where an item carrying a uid came from and went to (D5).
-- Only items with a uid are recorded; the same uid in two characters at once is a duplication.

CREATE TABLE item_ledger (
    ledger_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_uid       uuid        NOT NULL,
    def_id         text        NOT NULL,
    from_character bigint REFERENCES character (character_id),
    to_character   bigint REFERENCES character (character_id),
    reason         text        NOT NULL,
    at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX item_ledger_item_uid ON item_ledger (item_uid);
