-- Topic taxonomy plus the prerequisite DAG over it.
--
-- Explicit ids are used so later seed migrations can reference tags without sub-selects;
-- the identity sequence is realigned at the end of each seed migration.

INSERT INTO tags (id, name)
VALUES (1, 'implementation'),
       (2, 'arrays'),
       (3, 'strings'),
       (4, 'math'),
       (5, 'sorting'),
       (6, 'hashing'),
       (7, 'prefix-sum'),
       (8, 'two-pointers'),
       (9, 'sliding-window'),
       (10, 'binary-search'),
       (11, 'stack'),
       (12, 'queue'),
       (13, 'linked-list'),
       (14, 'recursion'),
       (15, 'backtracking'),
       (16, 'greedy'),
       (17, 'heap'),
       (18, 'tree'),
       (19, 'graph'),
       (20, 'bfs'),
       (21, 'dfs'),
       (22, 'shortest-path'),
       (23, 'dsu'),
       (24, 'dp'),
       (25, 'bitmask'),
       (26, 'number-theory'),
       (27, 'combinatorics'),
       (28, 'geometry'),
       (29, 'segment-tree'),
       (30, 'trie');

-- Edge (tag_id, prerequisite_tag_id) reads as "tag_id depends on prerequisite_tag_id".
-- Roots of the DAG are 'implementation' (1) and 'math' (4).
INSERT INTO tag_prerequisites (tag_id, prerequisite_tag_id)
VALUES (2, 1),   -- arrays        <- implementation
       (3, 2),   -- strings       <- arrays
       (5, 2),   -- sorting       <- arrays
       (6, 2),   -- hashing       <- arrays
       (7, 2),   -- prefix-sum    <- arrays
       (8, 2),   -- two-pointers  <- arrays
       (8, 5),   -- two-pointers  <- sorting
       (9, 8),   -- sliding-window<- two-pointers
       (10, 5),  -- binary-search <- sorting
       (11, 2),  -- stack         <- arrays
       (12, 2),  -- queue         <- arrays
       (13, 2),  -- linked-list   <- arrays
       (14, 1),  -- recursion     <- implementation
       (15, 14), -- backtracking  <- recursion
       (16, 5),  -- greedy        <- sorting
       (17, 5),  -- heap          <- sorting
       (18, 14), -- tree          <- recursion
       (19, 11), -- graph         <- stack
       (19, 12), -- graph         <- queue
       (20, 19), -- bfs           <- graph
       (20, 12), -- bfs           <- queue
       (21, 19), -- dfs           <- graph
       (21, 14), -- dfs           <- recursion
       (22, 20), -- shortest-path <- bfs
       (22, 17), -- shortest-path <- heap
       (23, 19), -- dsu           <- graph
       (24, 14), -- dp            <- recursion
       (25, 24), -- bitmask       <- dp
       (25, 4),  -- bitmask       <- math
       (26, 4),  -- number-theory <- math
       (27, 4),  -- combinatorics <- math
       (28, 4),  -- geometry      <- math
       (29, 18), -- segment-tree  <- tree
       (29, 7),  -- segment-tree  <- prefix-sum
       (30, 18), -- trie          <- tree
       (30, 3);  -- trie          <- strings

SELECT setval(pg_get_serial_sequence('tags', 'id'), (SELECT MAX(id) FROM tags));
