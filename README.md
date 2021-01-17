# Qortal Project - Official Repo

## Build / run

- Requires Java 11. OpenJDK 11 recommended over Java SE.
- Install Maven
- Use Maven to fetch dependencies and build: `mvn clean package`
- Built JAR should be something like `target/qortal-1.0.jar`
- Create basic *settings.json* file: `echo '{}' > settings.json`
- Run JAR in same working directory as *settings.json*: `java -jar target/qortal-1.0.jar`
- Wrap in shell script, add JVM flags, redirection, backgrounding, etc. as necessary.
- Or use supplied example shell script: *run.sh*

## Options for `settings.json` file

_Attributes and their default values are accurate as of 22/08/2020_

#### Example implementation:


```json
{
  "uiLocalServers": [
    "localhost",
    "127.0.0.1",
    "172.24.1.1",
    "qor.tal"
  ],
  "minBlockchainPeers": 5,
  "maxPeers": 32
}
```

|Key|Default value|
|---|---|
|MAINNET_LISTEN_PORT|12392|
|TESTNET_LISTEN_PORT|62392|
|MAINNET_API_PORT|12391|
|TESTNET_API_PORT|62391|
|bindAddress|::|
|uiPort|12388|
|uiLocalServers|"localhost", "127.0.0.1", "172.24.1.1", "qor.tal"|
|uiRemoteServers|"node1.qortal.org", "node2.qortal.org", "node3.qortal.org", "node4.qortal.org", "node5.qortal.org", "node6.qortal.org", "node7.qortal.org", "node8.qortal.org", "node9.qortal.org", "node10.qortal.org"|
|apiEnabled|true|
|apiPort|0|
|apiWhitelist|"::1", "127.0.0.1"|
|apiRestricted|false|
|apiLoggingEnabled|false|
|apiDocumentationEnabled|false|
|sslKeystorePathname|null|
|wipeUnconfirmedOnStart|false|
|maxUnconfirmedPerAccount|25|
|maxTransactionTimestampFuture|86400000|
|autoUpdateEnabled|true|
|repositoryBackupInterval|0|
|showBackupNotification|false|
|isTestNet|false|
|listenPort|0|
|minBlockchainPeers|5|
|minOutboundPeers|5|
|maxPeers|32|
|maxNetworkThreadPoolSize|20|
|networkPoWComputePoolSize|2|
|slowQueryThreshold|null|
|repositoryPath|db|
|autoUpdateRepos|"https://github.com/Qortal/qortal/raw/%s/qortal.update", "https://raw.githubusercontent.com@151.101.16.133/Qortal/qortal/%s/qortal.update"|
|ntpServers|"pool.ntp.org", "0.pool.ntp.org", "1.pool.ntp.org", "2.pool.ntp.org", "3.pool.ntp.org", "cn.pool.ntp.org", "0.cn.pool.ntp.org", "1.cn.pool.ntp.org", "2.cn.pool.ntp.org", "3.cn.pool.ntp.org"|
|testNtpOffset|null|
