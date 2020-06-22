package nms.rib.verticle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.thetransactioncompany.jsonrpc2.JSONRPC2ParseException;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import net.named_data.jndn.Name;
import nms.forwarder.api.EventBusEndpoint;
import nms.rib.Fib;
import nms.rib.Rib;
import nms.rib.RibAction;
import nms.rib.Route;
import nms.rib.commands.FibCommand;
import nms.rib.service.RibHandler;
import nms.rib.service.RibService;
import nms.rib.service.RibServiceImpl;

public class RibVerticle extends AbstractVerticle {

	private static final Logger LOG = LoggerFactory.getLogger(RibVerticle.class);
	private static String RIB_EVENTBUS_ADDRESS = "rib-verticle.eventbus";
	private static String FORWARDER_EVENTBUS_ADDRESS = "fw-verticle.eventbus";
	private RibService ribService;
	private Fib currentFib;

	@Override
	public void start(Promise<Void> promise) {
		LOG.info("starting " + this.getClass().getName());
		this.ribService = new RibServiceImpl();
		LOG.info("instantiated RIB Service");

		currentFib = new Fib(); // the agent starts with an empty fib
		// setup EventBus
		this.consumeEventBus(RIB_EVENTBUS_ADDRESS, promise);

	}

	private void consumeEventBus(String address, Promise<Void> promise) {
		vertx.eventBus().consumer(address, (message) -> {
			String body = message.body().toString();
			JSONRPC2Request req;
			try {
				req = JSONRPC2Request.parse(body);

			} catch (JSONRPC2ParseException e) {
				LOG.error(e.getMessage());
				return;
			}

			String method = req.getMethod();
			LOG.info("[eventbus] method = " + method);

			Map<String, Object> params = req.getNamedParams();
			
			if (method.equals(EventBusEndpoint.ADD_ROUTE.getName())) {
				Name prefix = new Name( (String) params.get("Prefix"));
				Number faceNumber = (Number) params.get("FaceId");
				int faceId = faceNumber.intValue();
				Number originNumber = (Number) params.get("Origin");
				int origin = originNumber.intValue();
				LOG.info("Prefix: " + prefix.toUri() + ", FaceId: " + faceId + ", Origin: " +  origin );
				
				
				ribService.addRoute(new Route(prefix, faceId, origin)).onComplete(ar -> {
					sendFibCommands(ar.result()).onComplete(ar1 -> {
						if (ar1.succeeded()) {
							message.reply(new JsonObject().put("status", "success").put("message", "FIB updated"));
						} else {
							message.reply(new JsonObject().put("status", "error").put("message", "something went wrong"));
						}
					});
				});
//				ribService.addRoute(new Route(name, faceId, origin), new RibHandler() {
//					@Override
//					public void handleRib(Rib rib) {
//						sendFibCommands(rib);
//					}
//				});
			}

			if (method.equals(EventBusEndpoint.REMOVE_ROUTE.getName())) {
				Name name = new Name( (String) params.get("Prefix"));
				Number faceNumber = (Number) params.get("FaceId");
				int faceId = faceNumber.intValue();
				Number originNumber = (Number) params.get("Origin");
				int origin = originNumber.intValue();
				
				ribService.removeRoute(new Route(name, faceId, origin)).onComplete(ar -> {
					sendFibCommands(ar.result()).onComplete(ar1 -> {
						if (ar1.succeeded()) {
							message.reply(new JsonObject().put("status", "success").put("message", "FIB updated"));
						} else {
							message.reply(new JsonObject().put("status", "error").put("message", "something went wrong"));
						}
					});
				});
//				ribService.removeRoute(new Route(name, faceId, origin), new RibHandler() {
//					@Override
//					public void handleRib(Rib rib) {
//						sendFibCommands(rib);
//					}
//				});
			}
		});	
		promise.complete();
	}

	private Future<Void> sendFibCommands(Rib rib) {
		Promise<Void> promise = Promise.promise();
		Fib fib = rib.toFib();
		// get the fib commands that need to be sent to the forward verticle
		List<FibCommand> fibCommands = fib.compare(currentFib);
		List<Future> allFutures = new ArrayList<>();
		fibCommands.forEach(command -> {
			allFutures.add(sendCommandFuture(command));
		});
		CompositeFuture.all(allFutures).onComplete(ar -> {
			if (ar.succeeded()) {
				LOG.debug("all commands were executed successfully");
				promise.complete();
			} else {
				LOG.debug("something went bad");
				promise.fail(ar.cause());
			}
		});
		return promise.future();
	}

	private Future<Void> sendCommandFuture(FibCommand command) {
		Promise<Void> promise = Promise.promise();
		LOG.info("command: " + command);
		vertx.eventBus().request(FORWARDER_EVENTBUS_ADDRESS, command.toJsonRpcRequest(), ar -> {
			if (ar.succeeded()) {
				promise.complete();
			} else {
				promise.fail(ar.cause());
			}
		});
		return promise.future();
	}

	@Override
	public void stop() {
		LOG.info("stopping " + this.getClass().getName());
	}

}