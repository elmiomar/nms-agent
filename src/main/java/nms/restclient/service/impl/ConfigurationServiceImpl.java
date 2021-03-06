package nms.restclient.service.impl;

import com.google.gson.Gson;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import nms.restclient.Configuration;
import nms.restclient.EntryPoint;

import nms.restclient.service.ConfigurationService;

public class ConfigurationServiceImpl implements ConfigurationService {

	private WebClient webClient;
	private EntryPoint entryPoint;
	private Configuration runningConfiguration;
	private String token;
	private CredentialsProvider credsProvider;

	private static String CANDIDATE_CONFIG_ENDPOINT = "/configuration/candidate-config";
	private static String RUNNING_CONFIG_ENDPOINT = "/configuration/running-config";
	private static long CONFIG_PERIOD = 60000; // delay the delay in milliseconds, after which the timer will fire

	public ConfigurationServiceImpl(WebClient webClient, EntryPoint entryPoint) {
		this.webClient = webClient;
		this.entryPoint = entryPoint;
		this.credsProvider = new CredentialsProvider("data.properties");
		
		  TokenProvider.getFreshToken(credsProvider.getUsername(),credsProvider.getPassword())
		  .onComplete(ar -> { if (ar.succeeded()) {
		  this.token = ar.result(); } else { System.out.println("error"); } });
		 
	}

	@Override
	public Future<Configuration> getConfiguration() {
		Promise<Configuration> promise = Promise.promise();
		HttpRequest<Buffer> request = webClient.get(entryPoint.getPort(), entryPoint.getHost(), CANDIDATE_CONFIG_ENDPOINT);
		authorize(request);
		request.send(ar -> {
			if (ar.succeeded()) {
				HttpResponse<Buffer> response = ar.result();
				if (response.statusCode() == 200) {
					Configuration candidate = new Gson().fromJson(response.bodyAsString(), Configuration.class);
				//	Configuration config =  compare(runningConfiguration,candidate);
					updateCurrentConfig(candidate);
					promise.complete(candidate);
				} else {
					if (response.statusCode() == 401) {
						refreshTokenAndRetry(request).onComplete(ar1 -> {
							if (ar1.succeeded()) {
								promise.complete(ar1.result());
							}
						});
					}
					if (response.statusCode() == 404) {
						promise.fail("candidate configuration not found");
					}
				}
			} else {
				promise.fail("unable to retrieve configuration from controller, abort");
			}
		});
		return promise.future();
	}
	
	@Override
	public Future<Void> sendRunningConfiguration(Configuration runningConfiguration) {
		Promise<Void> promise = Promise.promise();
		HttpRequest<Buffer> request = webClient.put(entryPoint.getPort(), entryPoint.getHost(), RUNNING_CONFIG_ENDPOINT );
		authorize(request);
		request
		.sendJsonObject(runningConfiguration.toJsonObject(),ar ->  {
			if(ar.succeeded()) {
				HttpResponse<Buffer> response = ar.result();
				if(response.statusCode() == 200) {
					promise.complete();
				} else {
				  if (response.statusCode() == 401) {
					  refreshTokenAndRetry(request).onComplete(ar1 -> {
							if (ar1.succeeded()) {
								promise.complete();
							}
						});
				  }
				  if(response.statusCode() == 400) {
					  promise.fail("Incorrect Config object.");
				  }
				}
		   } else {
				promise.fail("Failed to send the Config ");
			}
		  });
		
		return promise.future();
	}

	@Override
	public void getConfigurationPeriodically(Vertx vertx, Handler<AsyncResult<Configuration>> handler) {
		vertx.setPeriodic(CONFIG_PERIOD, id -> this.getConfiguration().onComplete(handler));
	}

	private Future<Configuration> refreshTokenAndRetry(HttpRequest<Buffer> request) {
		Promise<Configuration> promise = Promise.promise();
		TokenProvider.getFreshToken(credsProvider.getUsername(), credsProvider.getPassword()).onComplete(ar -> {
			if (ar.succeeded()) {
				request.send(ar1 -> {
					if (ar1.succeeded()) {
						HttpResponse<Buffer> response = ar1.result();
						Configuration config = new Gson().fromJson(response.bodyAsString(), Configuration.class);
						promise.complete(config);
					} else {
						promise.fail("something went wrong, can't retrieve configuration");
					}
				});
			} else {
				promise.fail("can't get a new token");
			}
		});
		return promise.future();
	}

	private void updateCurrentConfig(Configuration config) {
		this.setRunningConfiguration(config);
	}

	private void authorize(HttpRequest<Buffer> request) {
		// if token has expiration info, we can test and check if it's expired then get
		// a new token;
		request.bearerTokenAuthentication(token);
	}

	public Configuration getRunningConfiguration() {
		return runningConfiguration;
	}

	public void setRunningConfiguration(Configuration runningConfiguration) {
		this.runningConfiguration = runningConfiguration;
	}
	
	
	
	

}
