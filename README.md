# Helium

Wire Http Client written in Java

Helium implements WireAPI defined in Xenon repository,
making HTTP Rest calls to the Wire backend.

Current backend API targeted version is v6, set host and version with environmental variable "WIRE_API_HOST".


Not all the APIs available are implemented, but Helium should help with the most common task of an SDK to send/receive messages and manage the user's 
data.
This API is targeted towards clients or anything that logs in as a User. For a service/bot/server approach take a look at Lithium
(same API contract, but using other endpoints designed for services).

## How to use it?

- In your `pom.xml`:

```xml

<dependencies>
    <dependency>
        <groupId>com.wire</groupId>
        <artifactId>helium</artifactId>
        <version>x.y.z</version>
    </dependency>
</dependencies>
```

Create a `LoginClient`, passing a JAX-RS (e.g. Jersey Client) http client previously created, then obtain the user's token
with the login call. Then, create a `API` instance with the same http-client and the user's token.

## How to build the project

Requirements:

- [Java >= 11](http://www.oracle.com)
- [Maven](https://maven.apache.org)
- [Cryptobox4j](https://github.com/wireapp/cryptobox4j)
