package nl.sikken.bertrik;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.PropertyConfigurator;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.sikken.bertrik.hab.DecodeException;
import nl.sikken.bertrik.hab.EPayloadEncoding;
import nl.sikken.bertrik.hab.ExpiringCache;
import nl.sikken.bertrik.hab.PayloadDecoder;
import nl.sikken.bertrik.hab.Sentence;
import nl.sikken.bertrik.hab.habitat.HabReceiver;
import nl.sikken.bertrik.hab.habitat.HabitatUploader;
import nl.sikken.bertrik.hab.habitat.IHabitatRestApi;
import nl.sikken.bertrik.hab.habitat.Location;
import nl.sikken.bertrik.hab.ttn.TtnListener;
import nl.sikken.bertrik.hab.ttn.TtnMessage;
import nl.sikken.bertrik.hab.ttn.TtnMessageGateway;

/**
 * Bridge between the-things-network and the habhub network.
 * 
 */
public final class TtnHabBridge {

    private static final Logger LOG = LoggerFactory.getLogger(TtnHabBridge.class);
    private static final String CONFIG_FILE = "ttnhabbridge.properties";

    private final TtnListener ttnListener;
    private final HabitatUploader habUploader;
    private final PayloadDecoder decoder;
    private final ObjectMapper mapper;
    private final ExpiringCache gwCache;

    /**
     * Main application entry point.
     * 
     * @param arguments application arguments (none taken)
     * @throws IOException in case of a problem reading a config file
     * @throws MqttException in case of a problem starting MQTT client
     */
    public static void main(String[] arguments) throws IOException, MqttException {
        PropertyConfigurator.configure("log4j.properties");

        ITtnHabBridgeConfig config = readConfig(new File(CONFIG_FILE));
        TtnHabBridge app = new TtnHabBridge(config);

        Thread.setDefaultUncaughtExceptionHandler(app::handleUncaughtException);

        app.start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
    }

    /**
     * Constructor.
     * 
     * @param config the application configuration
     */
    private TtnHabBridge(ITtnHabBridgeConfig config) {
        this.ttnListener = new TtnListener(this::handleTTNMessage, 
                                           config.getTtnMqttUrl(), config.getTtnAppId(), config.getTtnAppKey());
        IHabitatRestApi restApi = 
                HabitatUploader.newRestClient(config.getHabitatUrl(), config.getHabitatTimeout());
        this.habUploader = new HabitatUploader(restApi);
        this.mapper = new ObjectMapper();
        this.decoder = new PayloadDecoder(EPayloadEncoding.parse(config.getTtnPayloadEncoding()));
        this.gwCache = new ExpiringCache(config.getTtnGwCacheExpiry());
    }

    /**
     * Starts the application.
     * 
     * @throws MqttException in case of a problem starting MQTT client
     */
    private void start() throws MqttException {
        LOG.info("Starting TTN-HAB bridge application");

        // start sub-modules
        habUploader.start();
        ttnListener.start();

        LOG.info("Started TTN-HAB bridge application");
    }

    /**
     * Handles an incoming TTN message
     * 
     * @param now message arrival time
     * @param topic the topic on which the message was received
     * @param textMessage the message contents
     */
    private void handleTTNMessage(Instant now, String topic, String textMessage) {
        try {
            // decode from JSON
            TtnMessage message = mapper.readValue(textMessage, TtnMessage.class);
            Sentence sentence = decoder.decode(message);
            String line = sentence.format();
            
            // collect list of listeners 
            List<HabReceiver> receivers = new ArrayList<>();
            for (TtnMessageGateway gw : message.getMetaData().getMqttGateways()) {
                String gwName = gw.getId();
                Location gwLocation = gw.getLocation();
                HabReceiver receiver = new HabReceiver(gwName, gwLocation);
                receivers.add(receiver);

                // send listener data only if it has a valid location and hasn't been sent recently
                if (gwLocation.isValid() && gwCache.add(gwName, now)) {
                    habUploader.scheduleListenerDataUpload(receiver, now);
                }
            }

            // send payload telemetry data
            habUploader.schedulePayloadTelemetryUpload(line, receivers, now);
        } catch (IOException e) {
            LOG.warn("JSON unmarshalling exception '{}' for {}", e.getMessage(), textMessage);
        } catch (DecodeException e) {
            LOG.warn("Payload decoding exception: {}", e.getMessage());
        } catch (Exception e) {
        	LOG.trace("Caught unhandled exception", e);
        	LOG.error("Caught unhandled exception:" + e.getMessage());
        }
    }

    /**
     * Stops the application.
     * 
     * @throws MqttException
     */
    private void stop() {
        LOG.info("Stopping TTN HAB bridge application");
        ttnListener.stop();
        habUploader.stop();
        LOG.info("Stopped TTN HAB bridge application");
    }
    
    /**
     * Handles uncaught exceptions: log it and stop the application.
     * 
     * @param t the thread
     * @param e the exception
     */
    private void handleUncaughtException(Thread t, Throwable e) {
        LOG.error("Caught unhandled exception, application will be stopped ...", e);
        stop();
    }
    
    private static ITtnHabBridgeConfig readConfig(File file) throws IOException {
        TtnHabBridgeConfig config = new TtnHabBridgeConfig();
        try (FileInputStream fis = new FileInputStream(file)) {
            config.load(fis);
        } catch (IOException e) {
            LOG.warn("Failed to load config {}, writing defaults", file.getAbsoluteFile());
            try (FileOutputStream fos = new FileOutputStream(file)) {
                config.save(fos);
            }
        }
        return config;
    }

}
