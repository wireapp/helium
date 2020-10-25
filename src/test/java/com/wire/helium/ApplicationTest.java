package com.wire.helium;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import com.wire.xenon.MessageHandlerBase;
import com.wire.xenon.WireClient;
import com.wire.xenon.assets.MessageText;
import com.wire.xenon.assets.Picture;
import com.wire.xenon.backend.models.Conversation;
import com.wire.xenon.backend.models.User;
import com.wire.xenon.crypto.CryptoDatabase;
import com.wire.xenon.crypto.storage.JdbiStorage;
import com.wire.xenon.factories.CryptoFactory;
import com.wire.xenon.factories.StorageFactory;
import com.wire.xenon.models.AssetKey;
import com.wire.xenon.models.ImageMessage;
import com.wire.xenon.models.TextMessage;
import com.wire.xenon.state.JdbiState;
import com.wire.xenon.tools.Logger;
import com.wire.xenon.tools.Util;
import org.flywaydb.core.Flyway;
import org.junit.BeforeClass;
import org.junit.Test;
import org.skife.jdbi.v2.DBI;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.UUID;

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
                Logger.info("onText: received: %s", msg.getText());
            }

            @Override
            public void onImage(WireClient client, ImageMessage msg) {
                Logger.info("onImage: received: %d bytes, %s", msg.getSize(), msg.getMimeType());
                try {
                    final byte[] bytes = client.downloadAsset(msg.getAssetKey(), msg.getAssetToken(), msg.getSha256(), msg.getOtrKey());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        Application app = new Application(email, password, false, wsUrl)
                .addClient(client)
                .addCryptoFactory(getCryptoFactory())
                .addStorageFactory(getStorageFactory())
                .addHandler(messageHandlerBase);

        app.start();

        // Create WireClient without Conversation in order to create one
        WireClientImp wireClient = app.getWireClient(null);

        final User self = wireClient.getSelf();
        final UUID ottoUserId = wireClient.getUserId("ottothebot");

        // Create new conversation with Otto
        final Conversation conv = wireClient.createConversation("Test", null, Collections.singletonList(ottoUserId));

        // Create new WireClient this time for this newly created conversation
        wireClient = app.getWireClient(conv.id);

        // Add Echo bot into this conv (code: 59d7abe5-3850-4b34-8fe5-0bcd4bfad4e6:aba311a6-fb14-46c9-af1b-3cb454762ef2)
        final UUID serviceId = UUID.fromString("aba311a6-fb14-46c9-af1b-3cb454762ef2");
        final UUID providerId = UUID.fromString("59d7abe5-3850-4b34-8fe5-0bcd4bfad4e6");
        wireClient.addService(serviceId, providerId);

        // Send text
        wireClient.send(new MessageText("Hi there!"));

        // Send Image
        final byte[] bytes = Util.getResource("moon.jpg");
        Picture image = new Picture(bytes, "image/jpeg");
        AssetKey assetKey = wireClient.uploadAsset(image);
        image.setAssetKey(assetKey.key);
        image.setAssetToken(assetKey.token);
        wireClient.send(image);

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
