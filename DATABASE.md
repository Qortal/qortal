# Database

You can examine your node's database using [HSQLDB's "sqltool"](http://www.hsqldb.org/doc/2.0/util-guide/sqltool-chapt.html).
It's a good idea to install "rlwrap" (ReadLine wrapper) too as sqltool doesn't support command history/editing.

Typical command line for sqltool would be:
`rlwrap java -cp ${HSQLDB_JAR}:${SQLTOOL_JAR} org.hsqldb.cmdline.SqlTool --rcFile=${SQLTOOL_RC} qortal`

`${HSQLDB_JAR}` should be set with pathname where Maven downloaded hsqldb, 
typically `${HOME}/.m2/repository/org/hsqldb/hsqldb/2.7.4/hsqldb-2.7.4.jar`

`${SQLTOOL_JAR}` should be set with pathname where Maven downloaded sqltool.
The Qortal build does not include sqltool, so fetch the version matching hsqldb first,
e.g. `mvn dependency:get -Dartifact=org.hsqldb:sqltool:2.7.4`, which saves it to
`${HOME}/.m2/repository/org/hsqldb/sqltool/2.7.4/sqltool-2.7.4.jar`

`${SQLTOOL_RC}` should be set with pathname of a text file describing HSQLDB databases, 
e.g. `${HOME}/.sqltool.rc`, with contents like:

```
urlid qortal
url jdbc:hsqldb:file:db/blockchain
username SA
password
```
Above `url` component `file:db/blockchain` assumes you will call `sqltool` from directory containing `db/`.

Another idea is to assign a shell alias in your `.bashrc` like:
```
export HSQLDB_JAR=${HOME}/.m2/repository/org/hsqldb/hsqldb/2.7.4/hsqldb-2.7.4.jar
export SQLTOOL_JAR=${HOME}/.m2/repository/org/hsqldb/sqltool/2.7.4/sqltool-2.7.4.jar
alias sqltool='rlwrap java -cp ${HSQLDB_JAR}:${SQLTOOL_JAR} org.hsqldb.cmdline.SqlTool --rcFile=${SQLTOOL_RC}'
```
So you can simply type: `sqltool qortal`

Don't forget to use `SHUTDOWN;` before exiting sqltool so that database files are closed cleanly.

## Quick and dirty version

With `sqltool-2.7.4.jar` and `qortal.jar` in current directory, and database in `db/`

`java -cp qortal.jar:sqltool-2.7.4.jar org.hsqldb.cmdline.SqlTool --inlineRc=url=jdbc:hsqldb:file:db/blockchain`
