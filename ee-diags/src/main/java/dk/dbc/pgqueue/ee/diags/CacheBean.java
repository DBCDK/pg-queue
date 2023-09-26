package dk.dbc.pgqueue.ee.diags;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 *
 * @author Morten Bøgeskov (mb@dbc.dk)
 */
@ApplicationScoped
public class CacheBean {

    @Inject
    private HazelcastInstance hazelcastInstance;
    private IMap<String, Instant> map;

    @PostConstruct
    public void initialize() {
        map = hazelcastInstance.getMap("known-queues");
    }

    @Produces
    public IMap<String, Instant> getMap() {
        return map;
    }
}