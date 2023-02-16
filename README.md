# Troc

This is the artifact for the paper "Detecting Isolation Bugs via Transaction Oracle Construction". 
See [paper](http://www.tcse.cn/~cuiziyu20/papers/2023-icse-troc.pdf) to learn more details.

Troc (Transaction oracle construction) is a tool to automatically detect isolation bugs in DBMSs. We refer to isolation 
bugs as those bugs that design flaws or buggy implementations of DBMSs' transaction mechanisms can violate their claimed 
isolation levels.

An archived version of the artifact is available on Zenodo. See https://doi.org/10.5281/zenodo.7645649.

## Overview

The artifact contains two structure:

* cases/: Contains the test cases that can trigger the bugs found by Troc.
* src/main/java/troc/: Contains the source code of Troc.

## Requirements

[REQUIREMENTS.md](REQUIREMENTS.md) describes the required hardware and software of the artifact.

## Setup

[INSTALL.md](INSTALL.md) provides information on how to run the artifact and verify that it works correctly.

# Running Troc

## Compile

The following commands put all dependencies into the subdirectory target/lib/ and create a Jar for Troc:
```bash
cd troc
mvn package -Dmaven.test.skip=true
```

## Usage
Important Parameters:
* `--dbms`: The type of the target DBMS. Default: `mysql`, can also be `mariadb` and `tidb`
* `--host`: The host used to log into the target DBMS. Default: `127.0.0.1`
* `--port`: The port used to log into the target DBMS. Default: `3306`
* `--username`: The username used to log into the target DBMS. Default: `"root"`
* `--password`: The password used to log into the target DBMS. Default: `""`
* `--db`: The database name to run tests. Default: `test`
* `--table`: The table name to run tests. Default: `troc`
* `--set-case`: Whether you use a predefined test case as test input. Default: `false`
* `--case-file`: The path of the text file that contains the predefined test case. Default: `""`

After following the instructions to install our test version of the three DBMSs (i.e., MySQL, MariaDB and TiDB),
you will get some parameters required for running Troc:
* MySQL:
  * `host`: `127.0.0.1`
  * `port`: `10003`
  * `username`: `"root"`
  * `password`: `"root"`
* MariaDB:
  * `host`: `127.0.0.1`
  * `port`: `10004`
  * `username`: `"root"`
  * `password`: `"root"`
* TiDB:
  * `host`: `127.0.0.1`
  * `port`: `4000`
  * `username`: `"root"`
  * `password`: `""`

**NOTE:**
If you already have deployed these DBMSs, the relevant parameters need to be replaced with those of your deployed DBMSs.

## Troc testing

The following commands automatically generate test cases for testing DBMSs.

For example, we connect to MariaDB (`--dbms mysql --host 127.0.0.1 --port 10004 --username root --password root`) and run Troc in MariaDB. 
We specify that the name of the tested table is t (`--table t`).
```bash
cd target
java -jar troc*.jar --dbms mariadb --host 127.0.0.1 --port 10004 --username root --password root --table t
```

The outputs include four parts. All of them are recorded in `troc.log`.

* Connect to target DBMS.
```bash
01/25 14:37:24.663 INFO troc.Main main: Run tests for MariaDB in [DB test]-[Table t] on [127.0.0.1:10004]
```

* Generate a table.
```bash
01/25 14:37:24.818 INFO troc.Main main: Create new table.
01/25 14:37:25.908 INFO troc.Main main: CREATE TABLE t(c0 INT PRIMARY KEY, c1 INT)
01/25 14:37:25.977 INFO troc.Main main: Initial table:
View{
	1:[1, 1]
	2:[2, 2]
}
```

* Generate a transaction pair.
```bash
01/25 14:40:50.718 INFO troc.Main main: Generate new transaction pair.
01/25 14:40:51.789 INFO troc.Main main: Transaction 1:
Transaction{1, REPEATABLE_READ}, with statements:
	BEGIN;
	SELECT * FROM t WHERE TRUE;
	SELECT * FROM t WHERE TRUE;
	DELETE FROM t WHERE c0 = 1 OR c0 = 2;
	SELECT * FROM t WHERE TRUE;
	COMMIT;

01/25 14:40:51.789 INFO troc.Main main: Transaction 2:
Transaction{2, REPEATABLE_READ}, with statements:
	BEGIN;
	DELETE FROM t WHERE c0 = 1;
	COMMIT;
```

* Execute the transaction pair in the submitted order in target DBMS, perform transaction oracle analysis, and check results.

If no discrepancy is detected, Troc outputs the results.
```bash
01/25 14:41:02.053 INFO troc.TrocChecker main: Schedule: [1-0, 1-1, 2-0, 1-2, 1-3, 2-1, 2-2, 1-4, 1-5]
01/25 14:41:02.053 INFO troc.TrocChecker main: Input schedule: 1-1-2-1-1-2-2-1-1
01/25 14:41:02.053 INFO troc.TrocChecker main: Get execute result: Result:
Order:[1-0, 1-1, 2-0, 1-2, 1-3, 2-1(B), 1-4, 1-5, 2-1, 2-2]
Query Results:
	1-0: null
	1-1: [1, 1, 2, 2]
	2-0: null
	1-2: [1, 1, 2, 2]
	1-3: null
	2-1: null
	1-4: []
	1-5: null
	2-1: null
	2-2: null
FinalState: []
DeadBlock: false

01/25 14:41:02.053 INFO troc.TrocChecker main: MVCC-based oracle order: [1-0, 1-1, 2-0, 1-2, 1-3, 2-1(B), 1-4, 1-5, 2-1, 2-2]
01/25 14:41:02.053 INFO troc.TrocChecker main: MVCC-based oracle result: Result:
Order:[1-0, 1-1, 2-0, 1-2, 1-3, 2-1(B), 1-4, 1-5, 2-1, 2-2]
Query Results:
	1-0: null
	1-1: [1, 1, 2, 2]
	2-0: null
	1-2: [1, 1, 2, 2]
	1-3: null
	2-1: null
	1-4: []
	1-5: null
	2-1: null
	2-2: null
FinalState: []
DeadBlock: false
```

If Troc finds a discrepancy, i.e., a potential bug, it outputs a bug report, which contains its bug type, all essential 
elements for reproducing the bug, the constructed oracle as well as the actual execution result.
```bash
01/25 14:40:56.942 INFO troc.TrocChecker main: Error: Inconsistent query result
01/25 14:40:56.942 INFO troc.TrocChecker main: query: SELECT * FROM t WHERE TRUE
01/25 14:40:56.942 INFO troc.TrocChecker main: ============================= BUG REPORT
 -- Create Table SQL: CREATE TABLE t(c0 INT PRIMARY KEY, c1 INT) 
 -- InitializeStatements:
	INSERT INTO t(c0, c1) VALUES (1, 1);
	INSERT INTO t(c0, c1) VALUES (2, 2);
 -- Initial Table: 
View{
	1:[1, 1]
	2:[2, 2]
}

 -- Tx1: Transaction{1, REPEATABLE_READ}, with statements:
	BEGIN;
	SELECT * FROM t WHERE TRUE;
	SELECT * FROM t WHERE TRUE;
	DELETE FROM t WHERE c0 = 1 OR c0 = 2;
	SELECT * FROM t WHERE TRUE;
	COMMIT;

 -- Tx2: Transaction{2, REPEATABLE_READ}, with statements:
	BEGIN;
	DELETE FROM t WHERE c0 = 1;
	COMMIT;

 -- Input Schedule: 1-1-2-2-2-1-1-1-1
 -- Submitted Order: [1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
 -- Execution Result: Result:
Order:[1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
Query Results:
	1-0: null
	1-1: [1, 1, 2, 2]
	2-0: null
	2-1: null
	2-2: null
	1-2: [1, 1, 2, 2]
	1-3: null
	1-4: [1, 1]
	1-5: null
FinalState: []
DeadBlock: false

 -- Inferred Result: Result:
Order:[1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
Query Results:
	1-0: null
	1-1: [1, 1, 2, 2]
	2-0: null
	2-1: null
	2-2: null
	1-2: [1, 1, 2, 2]
	1-3: null
	1-4: []
	1-5: null
FinalState: []
DeadBlock: false
```

## Troc reproducing

This can be used to reproduce known bugs.
We have provided some test cases in the `cases` directory.

1. The following commands use predefined test case in text file as input.
For example, we test TiDB with test case in text file cases/test.txt as input.
```bash
cd target
java -jar troc*.jar --dbms tidb --host 127.0.0.1 --port 4000 --username root --set-case --case-file ../cases/test.txt --table t
```

The outputs of these commands include five parts. All of them are recorded in `troc.log`.

* Connect to target DBMS.
```bash
01/22 19:16:25.929 INFO troc.Main main: Run tests for TIDB in [DB test]-[Table t] on [127.0.0.1:4000]
```

* Read the test case that includes a table, a transaction pair and a submitted order.
```bash
01/22 19:16:26.055 INFO troc.Main main: Read database and transactions from file: .\\cases\\test.txt
01/22 19:16:27.410 INFO troc.Main main: Initial table:
View{
	1:[1, 1]
	2:[2, 2]
}

01/22 19:16:27.426 INFO troc.Main main: Read transactions from file:
Transaction{1, REPEATABLE_READ}, with statements:
	BEGIN;
	SELECT * FROM t WHERE TRUE;
	SELECT * FROM t WHERE TRUE;
	DELETE FROM t WHERE c1 = 1 OR c1 = 2;
	SELECT * FROM t WHERE TRUE;
	COMMIT;
Transaction{2, REPEATABLE_READ}, with statements:
	BEGIN;
	DELETE FROM t WHERE c1 = 1;
	COMMIT;

01/22 19:16:27.426 INFO troc.Main main: Get schedule from file: 1-1-2-2-2-1-1-1-1
```

* Execute the transaction pair in the submitted order in target DBMS to obtain actual execution results.
```bash
01/22 19:16:27.426 INFO troc.TrocChecker main: Check new schedule.
01/22 19:16:50.698 INFO troc.TrocChecker main: Schedule: [1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
01/22 19:16:50.698 INFO troc.TrocChecker main: Input schedule: 1-1-2-2-2-1-1-1-1
01/22 19:16:50.698 INFO troc.TrocChecker main: Get execute result: Result:
Order:[1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
Query Results:
	1-0: null
	1-1: [1, 1, 2, 2]
	2-0: null
	2-1: null
	2-2: null
	1-2: [1, 1, 2, 2]
	1-3: null
	1-4: [1, 1]
	1-5: null
FinalState: []
DeadBlock: false
```

* Perform transaction oracle analysis to infer expected execution results.
```bash
01/22 19:16:50.698 INFO troc.TrocChecker main: MVCC-based oracle order: [1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
01/22 19:16:50.698 INFO troc.TrocChecker main: MVCC-based oracle result: Result:
Order:[1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
Query Results:
	1-0: null
	1-1: [1, 1, 2, 2]
	2-0: null
	2-1: null
	2-2: null
	1-2: [1, 1, 2, 2]
	1-3: null
	1-4: []
	1-5: null
FinalState: []
DeadBlock: false
```

* Check results.

The first output means tested transaction pairs, tested cases and skip test cases that we can hardly determine it is an isolation bug.

The second output means there exist a potential bug.

The third output indicates the location of detected discrepancies.
```bash
01/22 19:16:50.698 INFO troc.TrocChecker main: txp: 1, all case: 1, skip: 0
01/22 19:16:50.698 INFO troc.TrocChecker main: Error: Inconsistent query result
01/22 19:16:50.698 INFO troc.TrocChecker main: query: SELECT * FROM t WHERE TRUE
```

2. The following commands input predefined test case as input from command line.

**NOTE:**
Please take care the format, which you can learn from the provided case 
files in "cases" directory.

For example, we test MySQL with test case in text file cases/text.txt as input from command line.
```bash
cd target 
java -jar troc*.jar --dbms mysql --host 127.0.0.1 --port 10003 --username root --password root --set-case --table t
```

* Show the output.
```bash
02/11 18:02:06.819 INFO troc.Main main: Run tests for MYSQL in [DB test]-[Table t] on [127.0.0.1:10003]
02/11 18:02:07.466 INFO troc.Main main: Read database and transactions from command line
```

* Input the test case.
```bash
CREATE TABLE t (c1 INT PRIMARY KEY, c2 INT)
INSERT INTO t VALUES(1, 1), (2, 2)

RR
BEGIN
SELECT * FROM t
SELECT * FROM t
DELETE FROM t WHERE c1 = 1 OR c1 = 2
SELECT * FROM t
COMMIT

RR
BEGIN
DELETE FROM t WHERE c1 = 1
COMMIT

1-1-2-2-2-1-1-1-1
END
```

* Show the outputs.
```bash
02/11 18:02:39.503 INFO troc.Main main: Initial table:
View{
        1:[1, 1]
        2:[2, 2]
}

02/11 18:02:39.516 INFO troc.Main main: Read transactions from file:
Transaction{1, REPEATABLE_READ}, with statements:
        BEGIN;
        SELECT * FROM t WHERE TRUE;
        SELECT * FROM t WHERE TRUE;
        DELETE FROM t WHERE c1 = 1 OR c1 = 2;
        SELECT * FROM t WHERE TRUE;
        COMMIT;
Transaction{2, REPEATABLE_READ}, with statements:
        BEGIN;
        DELETE FROM t WHERE c1 = 1;
        COMMIT;

02/11 18:02:39.518 INFO troc.Main main: Get schedule from file: 1-1-2-2-2-1-1-1-1
02/11 18:02:39.519 INFO troc.TrocChecker main: Check new schedule.
02/11 18:02:47.714 INFO troc.TrocChecker main: Schedule: [1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
02/11 18:02:47.716 INFO troc.TrocChecker main: Input schedule: 1-1-2-2-2-1-1-1-1
02/11 18:02:47.720 INFO troc.TrocChecker main: Get execute result: Result:
Order:[1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
Query Results:
        1-0: null
        1-1: [1, 1, 2, 2]
        2-0: null
        2-1: null
        2-2: null
        1-2: [1, 1, 2, 2]
        1-3: null
        1-4: [1, 1]
        1-5: null
FinalState: []
DeadBlock: false

02/11 18:02:47.720 INFO troc.TrocChecker main: MVCC-based oracle order: [1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
02/11 18:02:47.721 INFO troc.TrocChecker main: MVCC-based oracle result: Result:
Order:[1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
Query Results:
        1-0: null
        1-1: [1, 1, 2, 2]
        2-0: null
        2-1: null
        2-2: null
        1-2: [1, 1, 2, 2]
        1-3: null
        1-4: []
        1-5: null
FinalState: []
DeadBlock: false

02/11 18:02:47.722 INFO troc.TrocChecker main: txp: 1, all case: 1, skip: 0
02/11 18:02:47.724 INFO troc.TrocChecker main: Error: Inconsistent query result
02/11 18:02:47.725 INFO troc.TrocChecker main: query: SELECT * FROM t WHERE TRUE
```

# Bug List
Troc has found 12 unique bugs in three widely-used DBMSs, i.e., MySQL, MariaDB, and TiDB. Among them, 10 bugs are 
isolation bugs. The remaining 2 bugs can be triggered by using only one transaction, no matter what isolation level is used.

| ID  | DBMS    | Link                                         |  Status   | Isolation Bug |
|-----|---------|----------------------------------------------|:---------:|:-------------:|
| 1   | MySQL   | http://bugs.mysql.com/104833                 | Verified  |      Yes      |
| 2   | MySQL   | http://bugs.mysql.com/104986                 | Duplicate |      Yes      |
| 3   | MySQL   | http://bugs.mysql.com/105030                 | Duplicate |      Yes      |
| 4   | MySQL   | http://bugs.mysql.com/105670                 | Duplicate |      Yes      |
| 5   | MariaDB | https://jira.mariadb.org/browse/MDEV-26642   | Verified  |      Yes      |
| 6   | MariaDB | https://jira.mariadb.org/browse/MDEV-26643   | Verified  |      Yes      |
| 7   | MariaDB | https://jira.mariadb.org/browse/MDEV-26671   | Duplicate |      Yes      |
| 8   | MariaDB | https://jira.mariadb.org/browse/MDEV-27992   |   Fixed   |      Yes      |
| 9   | TiDB    | https://github.com/pingcap/tidb/issues/28092 | Verified  |      No       |
| 10  | TiDB    | https://github.com/pingcap/tidb/issues/28095 | Verified  |      No       |
| 11  | TiDB    | https://github.com/pingcap/tidb/issues/28212 | Verified  |      Yes      |
| 12  | TiDB    | https://github.com/pingcap/tidb/issues/30239 | Duplicate |      Yes      |
