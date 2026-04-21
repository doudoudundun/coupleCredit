ALTER TABLE todo_items
    MODIFY COLUMN status ENUM('open', 'done', 'missed') NOT NULL DEFAULT 'open';
