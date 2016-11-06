package fr.soat.hystrix.microservice;

import com.netflix.hystrix.*;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import fr.soat.hystrix.config.ClientJersey;
import fr.soat.hystrix.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.util.ArrayList;
import java.util.List;

import static fr.soat.hystrix.model.RemoteCallException.ExceptionType.*;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;

@Component
public class PublicTransportFinderService {

    private final String URI = "http://localhost:8091";
    @Autowired
    ClientJersey clientJersey;
    private HystrixCommandGroupKey PT_HYSTRIX_COMMAND_GROUP_KEY = HystrixCommandGroupKey.Factory.asKey("PublicTransport");
    private HystrixCommandKey PT_HYSTRIX_COMMAND_KEY = HystrixCommandKey.Factory.asKey("PublicTransport");
    private HystrixThreadPoolKey PT_HYSTRIX_POOL_KEY = HystrixThreadPoolKey.Factory.asKey("PublicTransport");

    public ItineraryResponse search(String from, String to) {

        Response response = null;
        final ItineraryResponse itineraryResponse;
        PTHystrixCommand hystrixCommand = null;
        try {
            UriBuilder uriBuilder = getUriBuilder(from, to);
            final WebTarget target = clientJersey.getClient().target(uriBuilder);
            final Invocation.Builder request = target.request(APPLICATION_JSON_TYPE);
            hystrixCommand = new PTHystrixCommand(request);
            response = hystrixCommand.execute();
        } catch (HystrixRuntimeException e) {
            if (hystrixCommand != null) {
                hystrixCommand.closeConnection();
            }
            switch (e.getFailureType()) {
                case TIMEOUT:
                    throw new RemoteCallException(TIMEOUT, e);
                case SHORTCIRCUIT:
                    throw new RemoteCallException(HYSTRIX_OPEN_CIRCUIT, e);
                case REJECTED_THREAD_EXECUTION:
                    throw new RemoteCallException(HYSTRIX_REJECTED_THREAD_EXECUTION, e);
                default:
                    throw new RemoteCallException(OTHER, e);
            }
        }

        if (response.getEntity() instanceof ItineraryResponse) {
            itineraryResponse = (ItineraryResponse) response.getEntity();
        } else {
            itineraryResponse = adapteRemoteResponse(response.readEntity(RemoteItineraryResponse.class));
        }
        return itineraryResponse;
    }

    private UriBuilder getUriBuilder(String from, String to) {
        return UriBuilder.fromUri(URI)
                .path("itineraries/publicTransportRemote/find")
                .queryParam("from", from)
                .queryParam("to", to);
    }

    private ItineraryResponse adapteRemoteResponse(RemoteItineraryResponse remoteResponse) {
        List<Itinerary> itineraries = adapteRemoteItineraries(remoteResponse.getItineraries());
        ItineraryResponse response = new ItineraryResponse(itineraries);
        return response;
    }

    private List<Itinerary> adapteRemoteItineraries(List<RemoteItinerary> remoteItineraries) {

        List<Itinerary> itineraries = new ArrayList<>();
        remoteItineraries.stream().forEach(iti -> {
            final Itinerary itinerary = Itinerary.builder().setLine(iti.getLine())
                    .setDeparturePoint(iti.getDeparturePoint())
                    .setArrivalAPoint(iti.getArrivalPoint())
                    .setDepartureTime(iti.getDepartureTime())
                    .setArrivalTime(iti.getArrivalTime())
                    .setDistance(iti.getDistance())
                    .setTransportMode(iti.getTransportMode())
                    .build();
            itineraries.add(itinerary);
        });

        return itineraries;
    }

    private class PTHystrixCommand extends HystrixCommand<Response> {

        private final Invocation.Builder request;

        private Response response = null;

        public PTHystrixCommand(Invocation.Builder request) {
            super(
                    Setter.withGroupKey(PT_HYSTRIX_COMMAND_GROUP_KEY)
                            .andCommandKey(PT_HYSTRIX_COMMAND_KEY)
                            .andThreadPoolKey(PT_HYSTRIX_POOL_KEY)
                            .andThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter()
                                    .withCoreSize(10))
                            .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                                    .withExecutionTimeoutInMilliseconds(3000)
                                    .withCircuitBreakerRequestVolumeThreshold(2)
                                    .withCircuitBreakerErrorThresholdPercentage(50)
                                    //.withFallbackEnabled(false)
                                    .withMetricsRollingStatisticalWindowInMilliseconds(10000)
                                    .withCircuitBreakerSleepWindowInMilliseconds(10000)));

            this.request = request;
        }

        @Override
        protected Response run() throws Exception {
            response = request.get();
            return response;
        }

        @Override
        protected Response getFallback() {
            boolean partialResponse = true;
            return Response.ok(new ItineraryResponse(partialResponse)).build();
        }

        public void closeConnection() {
            if (response != null) {
                response.close();
            }
        }
    }
}