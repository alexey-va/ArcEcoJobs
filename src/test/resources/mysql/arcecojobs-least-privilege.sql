CREATE USER 'arcecojobs_test'@'%' IDENTIFIED BY 'test-password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX
    ON `arcecojobs_exploration_test`.* TO 'arcecojobs_test'@'%';
FLUSH PRIVILEGES;
