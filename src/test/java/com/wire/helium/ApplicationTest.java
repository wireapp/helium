package com.wire.helium;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.wire.xenon.MessageHandlerBase;
import com.wire.xenon.WireClient;
import com.wire.xenon.assets.MessageText;
import com.wire.xenon.backend.models.Conversation;
import com.wire.xenon.crypto.CryptoDatabase;
import com.wire.xenon.crypto.storage.JdbiStorage;
import com.wire.xenon.factories.CryptoFactory;
import com.wire.xenon.factories.StorageFactory;
import com.wire.xenon.models.TextMessage;
import com.wire.xenon.state.JdbiState;
import com.wire.xenon.tools.Logger;
import org.flywaydb.core.Flyway;
import org.junit.BeforeClass;
import org.junit.Test;
import org.skife.jdbi.v2.DBI;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Collections;

/**
 * Created with IntelliJ IDEA.
 * User: dejankovacevic
 * Date: 24/10/2020
 * Time: 11:16
 */
public class ApplicationTest {
    private static final String url = "jdbc:postgresql://localhost/lithium";
    private static final DBI dbi = new DBI(url);

    @BeforeClass
    public static void before() throws Exception {
        Class<?> driverClass = Class.forName("org.postgresql.Driver");
        final Driver driver = (Driver) driverClass.newInstance();
        DriverManager.registerDriver(driver);

        // Migrate DB if needed
        Flyway flyway = Flyway
                .configure()
                .dataSource(url, null, null)
                .load();
        flyway.migrate();
    }

    @Test
    public void sendMessagesTest() throws Exception {
        String email = "dejan@wire.com";
        String password = "12345678";
        String wsUrl = "wss://prod-nginz-ssl.wire.com";

        Client client = ClientBuilder
                .newClient()
                .register(JacksonJsonProvider.class);

        final MessageHandlerBase messageHandlerBase = new MessageHandlerBase() {
            @Override
            public void onText(WireClient client, TextMessage msg) {
                Logger.info("onText: %s", msg.getText());
            }
        };

        Application app = new Application(email, password, false, wsUrl)
                .addClient(client)
                .addCryptoFactory(getCryptoFactory())
                .addStorageFactory(getStorageFactory())
                .addHandler(messageHandlerBase);

        app.start();

        UserClient wireClient = app.getWireClient(null);
        final Conversation conv = wireClient.createConversation("Test", null, Collections.emptyList());
        wireClient = app.getWireClient(conv.id);
        wireClient.send(new MessageText("Hi there!"));

        Thread.sleep(15 * 1000);
        app.stop();
    }

    public StorageFactory getStorageFactory() {
        return userId -> new JdbiState(userId, dbi);
    }

    public CryptoFactory getCryptoFactory() {
        return (userId) -> new CryptoDatabase(userId, new JdbiStorage(dbi));
    }
}
