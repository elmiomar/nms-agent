package nms.restclient;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nms.restclient.service.impl.CredentialsProvider;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebClientVerticle extends AbstractVerticle {
  
	private RestClient restClient;

	private static final Logger LOG = LoggerFactory.getLogger(WebClientVerticle.class);

	private static long CONFIG_PERIOD = 30000; // delay the delay in milliseconds, after which the timer will fire
	private static long STATUS_PERIOD = 100000; // 100s

	private static Handler<Long> statusTask;
	private static Handler<Long> configTask;

	private ConfigurationHandler configHandler;

	@Override
	public void start(Promise<Void> promise) throws Exception {
		super.start(promise);
		LOG.info("starting " + this.getClass().getName());
		this.restClient = getRestClient();
		this.configHandler = new ConfigurationHandler(vertx);
		CredentialsProvider provider = new CredentialsProvider("data.properties");
		LOG.info("Credentials: {}/{}",provider.getUsername(),provider.getPassword());
		this.login(provider.getUsername(), provider.getPassword()).onComplete(ar -> {
			if (ar.succeeded()) {
				LOG.info("agent connected succesfully");
				String token = ar.result();
				LOG.debug("TOKEN=" + token);
				this.restClient.setToken(token);

				this.sendStatus(new Handler<AsyncResult<Void>>() {
					@Override
					public void handle(AsyncResult<Void> ar) {
						if (ar.succeeded()) {
							LOG.info("sent status to controller");
						} else {
							LOG.info("cannot send status, check if controller is running");
						}
					}
				});

				this.pollConfiguration(new Handler<AsyncResult<Configuration>>() {
					@Override
					public void handle(AsyncResult<Configuration> ar) {
						if (ar.succeeded()) {
							LOG.info("retrieved new configuration from the controller");
							Configuration candidate = ar.result();
							LOG.info(candidate.toJsonObject().encodePrettily());
							configHandler.compare(candidate)
									.onComplete(new Handler<AsyncResult<List<JsonObject>>>() {
										@Override
										public void handle(AsyncResult<List<JsonObject>> ar) {
											if (ar.succeeded()) {
												LOG.debug("COMMANDS = {}", ar.result());
												configHandler.send(ar.result());
											}
										}
									});
						} else {
							LOG.info("unable to retrive the configuration: " + ar.result());
//							promise.fail("unable to retrive the configuration: " + ar.result());
						}
					}
				});
			} else {
				LOG.info("unable to login, reason: " + ar.cause());
//				promise.fail("unable to login, reason: " + ar.cause());
			}
		});
	}

	private RestClient getRestClient() {
		RestClient restClient = new RestClientImpl(vertx);
//		restClient.setRootCA(config().getString("root-ca"));
//		restClient.setPort(config().getInteger("http.port"));
//		restClient.setHost(config().getString("http.host"));
		return restClient;
	}

	private Future<String> login(String username, String password) {
		return this.restClient.login(username, password);

	}

	private void sendStatus(Handler<AsyncResult<Void>> handler) {
		statusTask = id -> {
			this.restClient.sendStatus().onComplete(handler);
			vertx.setTimer(STATUS_PERIOD, statusTask); // send every 100s
		};
		vertx.setTimer(1000, statusTask); // send first status after 5 second
	}

	private void pollConfiguration(Handler<AsyncResult<Configuration>> handler) {
		configTask = id -> {
			this.restClient.getConfiguration().onComplete(handler);
			vertx.setTimer(CONFIG_PERIOD, configTask); // refresh config every 150s
		};

		vertx.setTimer(1000, configTask ); // get initial config after 1 second
	}
		

	@Override
	public void stop(Promise<Void> promise) throws Exception {
		super.stop(promise);
		LOG.info("stopping " + this.getClass().getName());
	}
}
