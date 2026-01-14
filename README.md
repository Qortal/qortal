# Qortal Project - Qortal Core - Primary Repository
The Qortal Core is the blockchain and node component of the overall project. It contains the primary API, and ability to make calls to create transactions, and interact with the Qortal Blockchain Network. 

In order to run the Qortal Core, a machine with java 11+ installed is required. Minimum RAM specs will vary depending on settings, but as low as 4GB of RAM should be acceptable in most scenarios. 

Qortal is a complete infrastructure platform with a blockchain backend, it is capable of indefinite web and application hosting with no continual fees, replacement of DNS and centralized name systems and communications systems, and is the foundation of the next generation digital infrastructure of the world. Qortal is unique in nearly every way, and was written from scratch to address as many concerns from both the existing 'blockchain space' and the 'typical internet' as possible, while maintaining a system that is easy to use and able to run on 'any' computer. 

Qortal contains extensive functionality geared toward complete decentralization of the digital world. Removal of 'middlemen' of any kind from all transactions, and ability to publish websites and applications that require no continual fees, on a name that is truly owned by the account that registered it, or purchased it from another. A single name on Qortal is capable of being both a namespace and a 'username'. That single name can have an application, website, public and private data, communications, authentication, the namespace itself and more, which can subsequently be sold to anyone else without the need to change any type of 'hosting' or DNS entries that do not exist, email that doesn't exist, etc. Maintaining the same functionality as those replaced features of web 2.0. 

Over time Qortal has progressed into a fully featured environment catering to any and all types of people and organizations, and will continue to advance as time goes on. Brining more features, capability, device support, and available replacements for web2.0. Ultimately building a new, completely user-controlled digital world without limits. 

Qortal has no owner, no company on top of it, and is completely community built, run, and funded. A community-established and run group of developers known as the 'dev-group' or Qortal Development Group, make group_approval based decisions for the project's future. If you are a developer interested in assisting with the project, you meay reach out to the Qortal Development Group in any of the available Qortal community locations. Either on the Qortal network itself, or on one of the temporary centralized social media locations. 

Building the future one block at a time. Welcome to Qortal. 

# Building the Qortal Core from Source

## Build / Run

- Requires Java 11. OpenJDK 11 recommended over Java SE.
- Install Maven
- Use Maven to fetch dependencies and build: `mvn clean package`
- Update Maven dependencies: `mvn install` 
- Built JAR should be something like `target/qortal-1.0.jar`
- Create basic *settings.json* file: `echo '{}' > settings.json`
- Run JAR in same working directory as *settings.json*: `java -jar target/qortal-1.0.jar`
- Wrap in shell script, add JVM flags, redirection, backgrounding, etc. as necessary.
- Or use supplied example shell script: *start.sh*

## IntelliJ IDEA Configuration

- Run -> Edit Configurations
- Add New Application
- Name: qortal
- SDK: java 11
- Main Class: org.qortal.controller.Controller
- Program arguments: settings.json -Dlog4j.configurationFile=log4j2.properties -ea
- Environment variables: Djava.net.preferIPv4Stack=false

# Using a pre-built Qortal 'jar' binary

If you prefer to utilize a released version of Qortal, you may do so by downloading one of the available releases from the releases page, that are also linked on https://qortal.org and https://qortal.dev. 

# Learning Q-App Development

https://qortal.dev contains dev documentation for building JS/React (and other client-side languages) applications or 'Q-Apps' on Qortal. Q-Apps are published on Registered Qortal Names, and aside from a single Name Registration fee, and a fraction of QORT for a publish transaction, require zero continual costs. These applications get more redundant with each new access from a new Qortal Node, making your application faster for the next user to download, and stronger as time goes on. Q-Apps live indefinitely in the history of the blockchain-secured Qortal Data Network (QDN).

# How to learn more

If the project interests you, you may learn more from the various web2 and QDN based websites focused on introductory information. 

https://qortal.org - primary internet presence 
https://qortal.dev - secondary and development focused website with links to many new developments and documentation
https://wiki.qortal.org - community built and managed wiki with detailed information regarding the project

links to telegram and discord communities are at the top of https://qortal.org as well. 
