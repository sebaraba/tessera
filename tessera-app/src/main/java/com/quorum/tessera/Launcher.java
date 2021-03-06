package com.quorum.tessera;

import com.quorum.tessera.config.CommunicationType;
import com.quorum.tessera.config.Config;
import com.quorum.tessera.config.ServerConfig;
import com.quorum.tessera.config.cli.CliDelegate;
import com.quorum.tessera.config.cli.CliResult;
import com.quorum.tessera.server.TesseraServer;
import com.quorum.tessera.server.TesseraServerFactory;
import com.quorum.tessera.service.locator.ServiceLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.JsonException;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * The main entry point for the application. This just starts up the application
 * in the embedded container.
 */
public class Launcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(Launcher.class);

    public static void main(final String... args) throws Exception {

        try {
            final CliResult cliResult = CliDelegate.instance().execute(args);

            if (cliResult.isSuppressStartup()) {
                System.exit(0);
            } else if (cliResult.getStatus() != 0) {
                System.exit(cliResult.getStatus());
            }

            if (!cliResult.getConfig().isPresent() && cliResult.isSuppressStartup()) {
                System.exit(cliResult.getStatus());
            }

            final Config config = cliResult.getConfig()
                    .orElseThrow(() -> new NoSuchElementException("No Config found. Tessera will not run"));

            runWebServer(config);

            System.exit(0);

        } catch (final ConstraintViolationException ex) {
            Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();

            for (ConstraintViolation<?> violation : violations) {
                System.out.println("Config validation issue: " + violation.getPropertyPath() + " " + violation.getMessage());
            }
            System.exit(1);
        } catch (com.quorum.tessera.config.ConfigException ex) {
            Throwable cause = resolveRootCause(ex);

            if (JsonException.class.isInstance(cause)) {
                System.err.println("Invalid json, error is " + cause.getMessage());
            } else {
                System.err.println(Objects.toString(cause));
            }
            System.exit(3);
        } catch (com.quorum.tessera.config.cli.CliException ex) {
            System.err.println(ex.getMessage());
            System.exit(4);
        } catch (Throwable ex) {

            Optional.ofNullable(ex.getMessage()).ifPresent(System.err::println);
            System.exit(2);
        }
    }

    private static Throwable resolveRootCause(Throwable ex) {
        if (Objects.nonNull(ex.getCause())) {
            return resolveRootCause(ex.getCause());
        }
        return ex;
    }

    private static void runWebServer(final Config config) throws Exception {

        ServiceLocator serviceLocator = ServiceLocator.create();

        Set<Object> services = serviceLocator.getServices("tessera-spring.xml");

        TesseraServerFactory restServerFactory = TesseraServerFactory.create(CommunicationType.REST);

        TesseraServerFactory grpcServerFactory = TesseraServerFactory.create(CommunicationType.GRPC);

        TesseraServerFactory unixSocketServerFactory = TesseraServerFactory.create(CommunicationType.UNIX_SOCKET);

        //TODO - we should only need one netty factory that is able to deal with any type of ServerConfig
        List<TesseraServer> servers = new ArrayList<>();
        for(ServerConfig serverConfig : config.getServerConfigs()){
            TesseraServer tesseraServer = null;
            switch (serverConfig.getCommunicationType()){
                case GRPC:
                    tesseraServer = grpcServerFactory.createServer(serverConfig, services);
                    break;
                case REST:
                    tesseraServer = restServerFactory.createServer(serverConfig, services);
                    break;
                case UNIX_SOCKET:
                    tesseraServer = unixSocketServerFactory.createServer(serverConfig, services);
                    break;
            }
            if (null != tesseraServer) {
                servers.add(tesseraServer);
            }
        }

        CountDownLatch countDown = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                for (TesseraServer ts : servers){
                    ts.stop();
                }
            } catch (Exception ex) {
                LOGGER.error(null, ex);
            } finally {
                countDown.countDown();
            }
        }));

        for (TesseraServer ts : servers){
            ts.start();
        }

        countDown.await();
    }

}
