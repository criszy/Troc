# Installation Instructions

This document contains information on how to run the artifact and verify that it works correctly.

## Requirements
* Java 11 or above
* [Maven](https://maven.apache.org/) (`sudo apt install maven` on Ubuntu)
* Sample DBMS: MariaDB

We take MariaDB as an example to show how to use Troc locally.

## Deploying sample DBMS

1. Download sample DBMS

This command downloads MariaDB (version 10.5.12) from Docker locally:
```bash
docker pull mariadb:10.5.12
```

2. Starting sample DBMS

This command starts MariaDB:
```bash
docker run -p 127.0.0.1:10004:3306 --name mariadb-10.5.12 -e MARIADB_ROOT_PASSWORD=root -d mariadb:10.5.12
```

## Testing the setup

1. Compile

The following commands put all dependencies into the subdirectory target/lib/ and create a Jar for Troc:
```bash
cd troc
mvn package -Dmaven.test.skip=true
```

2. Run with a test case

The following commands use predefined test case in text file as input:
```bash
cd target
java -jar troc*.jar --dbms mariadb --port 10004 --username root --password root --db test --set-case --case-file ../cases/test.txt --table t
```

The outputs of these commands include five parts. All the outputs are recorded in `troc.log`.

* Connect to MariaDB.
```bash
01/25 12:13:54.621 INFO troc.Main main: Run tests for MARIADB in [DB test]-[Table t] on [127.0.0.1:10004]
```

* Read the test case that includes a table, a transaction pair and a submitted order.
```bash
01/25 12:13:54.936 INFO troc.Main main: Read database and transactions from file: ../cases/test.txt
01/25 12:13:55.058 INFO troc.Main main: Initial table:
View{
        1:[1, 1]
        2:[2, 2]
}

01/25 12:13:55.072 INFO troc.Main main: Read transactions from file:
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

01/25 12:13:55.074 INFO troc.Main main: Get schedule from file: 1-1-2-2-2-1-1-1-1
```

* Execute the transaction pair in the submitted order in MariaDB to obtain actual execution results.
```bash
01/25 12:13:55.074 INFO troc.TrocChecker main: Check new schedule.
01/25 12:13:56.104 INFO troc.TrocChecker main: Schedule: [1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
01/25 12:13:56.105 INFO troc.TrocChecker main: Input schedule: 1-1-2-2-2-1-1-1-1
01/25 12:13:56.105 INFO troc.TrocChecker main: Get execute result: Result:
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
01/25 12:13:56.106 INFO troc.TrocChecker main: MVCC-based oracle order: [1-0, 1-1, 2-0, 2-1, 2-2, 1-2, 1-3, 1-4, 1-5]
01/25 12:13:56.106 INFO troc.TrocChecker main: MVCC-based oracle result: Result:
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
01/25 12:13:56.106 INFO troc.TrocChecker main: txp: 1, all case: 1, skip: 0
01/25 12:13:56.109 INFO troc.TrocChecker main: Error: Inconsistent query result
01/25 12:13:56.109 INFO troc.TrocChecker main: query: SELECT * FROM t WHERE TRUE
```