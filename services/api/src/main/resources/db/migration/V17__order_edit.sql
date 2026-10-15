-- Values a person corrected in the order table. Ours24 is never written: the API shows these over the Ours24 row.
-- Only edited cells are kept, one row per order and field. `work_day` is the operating day the edit was made for:
-- edited names (customerName, companyName) are deleted 90 days after it (OrderEdits.purge). `edited_by` is an account ID,
-- kept without a foreign key so the record of who edited stays when an account goes. Do not edit after merge.
CREATE TABLE order_edit (
    task_key   TEXT NOT NULL REFERENCES delivery_task (task_key),
    field_name TEXT NOT NULL,
    cell_value TEXT NOT NULL,
    work_day   DATE NOT NULL,
    edited_by  CHAR(36) NOT NULL,
    edited_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (task_key, field_name),
    CONSTRAINT order_edit_field CHECK (field_name IN ('customerName', 'companyName', 'address1', 'address2', 'postalCode')),
    CONSTRAINT order_edit_value CHECK (char_length(cell_value) BETWEEN 1 AND 200)
);

CREATE INDEX order_edit_day ON order_edit (work_day);
