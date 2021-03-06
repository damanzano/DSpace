/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.event;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.pool2.KeyedObjectPool;
import org.apache.commons.pool2.KeyedPooledObjectFactory;
import org.apache.commons.pool2.PoolUtils;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.apache.log4j.Logger;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Context;
import org.dspace.event.service.EventService;

/**
 * Class for managing the content event environment. The EventManager mainly
 * acts as a factory for Dispatchers, which are used by the Context to send
 * events to consumers. It also contains generally useful utility methods.
 * 
 * Version: $Revision$
 */
public class EventServiceImpl implements EventService
{
    /** log4j category */
    private Logger log = Logger.getLogger(EventServiceImpl.class);


    protected DispatcherPoolFactory dispatcherFactory = null;

    protected GenericKeyedObjectPoolConfig poolConfig = null;

    // Keyed FIFO Pool of event dispatchers
    protected KeyedObjectPool dispatcherPool = null;

    protected Map<String, Integer> consumerIndicies = null;

    protected String CONSUMER_PFX = "event.consumer.";

    protected EventServiceImpl()
    {
        initPool();
        log.info("Event Dispatcher Pool Initialized");
    }

    protected void initPool()
    {

        if (dispatcherPool == null)
        {

            // TODO EVENT Some of these pool configuration
            // parameters can live in dspace.cfg or a
            // separate configuration file

            // TODO EVENT Eviction parameters should be set

            poolConfig = new GenericKeyedObjectPoolConfig();
            poolConfig.setMaxTotalPerKey(100);
            poolConfig.setMaxIdlePerKey(5);
            poolConfig.setMaxTotal(100);

            try
            {
                dispatcherFactory = new DispatcherPoolFactory();


                dispatcherPool = PoolUtils
                        .synchronizedPool(new GenericKeyedObjectPool(
                                dispatcherFactory, poolConfig));

                enumerateConsumers();

            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

        }
    }

    @Override
    public Dispatcher getDispatcher(String name)
    {
        if (dispatcherPool == null)
        {
            initPool();
        }

        if (name == null)
        {
            name = DEFAULT_DISPATCHER;
        }

        try
        {
            return (Dispatcher) dispatcherPool.borrowObject(name);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Unable to aquire dispatcher named " + name, e);
        }

    }

    @Override
    public void returnDispatcher(String key, Dispatcher disp)
    {
        try
        {
            dispatcherPool.returnObject(key, disp);
        }
        catch (Exception e)
        {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public int getConsumerIndex(String consumerClass)
    {
        Integer index = (Integer) consumerIndicies.get(consumerClass);
        return index != null ? index.intValue() : -1;

    }

    protected void enumerateConsumers()
    {
        Enumeration propertyNames = ConfigurationManager.propertyNames();
        int bitSetIndex = 0;

        if (consumerIndicies == null)
        {
            consumerIndicies = new HashMap<String, Integer>();
        }

        while (propertyNames.hasMoreElements())
        {
            String ckey = ((String) propertyNames.nextElement()).trim();

            if (ckey.startsWith(CONSUMER_PFX) && ckey.endsWith(".class"))
            {
                String consumerName = ckey.substring(CONSUMER_PFX.length(),
                        ckey.length() - 6);

                consumerIndicies.put(consumerName, (Integer) bitSetIndex);
                bitSetIndex++;
            }
        }
    }

    protected class DispatcherPoolFactory implements KeyedPooledObjectFactory<String,Dispatcher>
    {

        // Prefix of keys in DSpace Configuration
        private static final String PROP_PFX = "event.dispatcher.";

        // Cache of event dispatchers, keyed by name, for re-use.
        protected Map<String, String> dispatchers = new HashMap<String, String>();

        public DispatcherPoolFactory()
        {
            parseEventConfig();
            log.info("");
        }

        public PooledObject<Dispatcher> wrap(Dispatcher d) {
           return new DefaultPooledObject<>(d);
        }

        @Override
        public PooledObject<Dispatcher> makeObject(String dispatcherName) throws Exception
        {
            Dispatcher dispatcher = null;
            String dispClass = dispatchers.get(dispatcherName);

            if (dispClass != null)
            {
                try
                {
                    // all this to call a constructor with an argument
                    final Class argTypes[] = { String.class };
                    Constructor dc = Class.forName(dispClass).getConstructor(
                            argTypes);
                    Object args[] = new Object[1];
                    args[0] = dispatcherName;
                    dispatcher = (Dispatcher) dc.newInstance(args);

                    // OK, now get its list of consumers/filters
                    String consumerKey = PROP_PFX + dispatcherName
                            + ".consumers";
                    String consumerList = ConfigurationManager
                            .getProperty(consumerKey);
                    if (consumerList == null)
                    {
                        throw new IllegalStateException(
                                "No Configuration entry found for consumer list of event Dispatcher: \""
                                        + consumerKey + "\"");
                    }

                    // Consumer list format:
                    // <consumer-name>:<mode>, ...
                    String[] consumerStanza = consumerList.trim().split(
                            "\\s*,\\s*");

                    // I think this should be a fatal error.. --lcs
                    if (consumerStanza.length < 1)
                    {
                        throw new IllegalStateException(
                                "Cannot initialize Dispatcher, malformed Configuration value for "
                                        + consumerKey);
                    }

                    ConsumerProfile consumerProfile = null;

                    // parts: 0 is name, part 1 is mode.
                    for (int i = 0; i < consumerStanza.length; i++)
                    {
                        consumerProfile = ConsumerProfile
                                .makeConsumerProfile(consumerStanza[i]);
                        consumerProfile.getConsumer().initialize();

                        dispatcher.addConsumerProfile(consumerProfile);
                    }
                }
                catch (NoSuchMethodException e)
                {
                    throw new IllegalStateException(
                            "Constructor not found for event dispatcher="
                                    + dispatcherName, e);
                }
                catch (InvocationTargetException e)
                {
                    throw new IllegalStateException(
                            "Error creating event dispatcher=" + dispatcherName,
                            e);
                }
                catch (ClassNotFoundException e)
                {
                    throw new IllegalStateException(
                            "Dispatcher/Consumer class not found for event dispatcher="
                                    + dispatcherName, e);
                }
                catch (InstantiationException e)
                {
                    throw new IllegalStateException(
                            "Dispatcher/Consumer instantiation failure for event dispatcher="
                                    + dispatcherName, e);
                }
                catch (IllegalAccessException e)
                {
                    throw new IllegalStateException(
                            "Dispatcher/Consumer access failure for event dispatcher="
                                    + dispatcherName, e);
                }
            }
            else
            {
                throw new IllegalStateException(
                        "Requested Dispatcher Does Not Exist In DSpace Configuration!");
            }

            return wrap(dispatcher);

        }

        @Override
        public void activateObject(String arg0, PooledObject<Dispatcher> arg1) throws Exception
        {
            // No-op
            return;

        }

        @Override
        public void destroyObject(String key, PooledObject<Dispatcher> pooledDispatcher)
                throws Exception
        {
            Context ctx = new Context();

            try {
                Dispatcher dispatcher = pooledDispatcher.getObject();

                for (Iterator ci = dispatcher.getConsumers()
                        .iterator(); ci.hasNext();)
                {
                    ConsumerProfile cp = (ConsumerProfile) ci.next();
                    if (cp != null)
                    {
                        cp.getConsumer().finish(ctx);
                    }
                }
            } catch (Exception e) {
                ctx.abort();
                throw e;
            }
        }

        @Override
        public void passivateObject(String arg0, PooledObject<Dispatcher> arg1) throws Exception
        {
            // No-op
            return;

        }

        @Override
        public boolean validateObject(String arg0, PooledObject<Dispatcher> arg1)
        {
            // No-op
            return false;
        }

        /**
         * Looks through the configuration for dispatcher configurations and
         * loads one of each into a HashMap. This Map will be used to clone new
         * objects when the pool needs them.
         * 
         * Looks for configuration properties like:
         * 
         * <pre>
         *  # class of dispatcher &quot;default&quot;
         *  event.dispatcher.default = org.dspace.event.BasicDispatcher
         *  # list of consumers followed by filters for each, format is
         *  #   &lt;consumerClass&gt;:&lt;filter&gt;[:&lt;anotherFilter&gt;..] , ...
         *  #  and each filter is expressed as:
         *  #    &lt;objectType&gt;[|&lt;objectType&gt; ...] + &lt;eventType&gt;[|&lt;eventType&gt; ..]
         *  org.dspace.event.TestConsumer:all+all, \
         *  org.dspace.eperson.SubscribeConsumer:Item+CREATE|DELETE:Collection+ADD, ...
         * </pre>
         * 
         */
        private void parseEventConfig()
        {
            Enumeration propertyNames = ConfigurationManager.propertyNames();
            while (propertyNames.hasMoreElements())
            {
                String ckey = ((String) propertyNames.nextElement()).trim();

                if (ckey.startsWith(PROP_PFX) && ckey.endsWith(".class"))
                {
                    String name = ckey.substring(PROP_PFX.length(), ckey
                            .length() - 6);
                    String dispatcherClass = ConfigurationManager
                            .getProperty(ckey);

                    // Can we grab all of the consumers configured for this
                    // dispatcher
                    // and store them also? Then there is no
                    // ConfigurationManager call
                    // upon other makeObject(key) requests resulting in a faster
                    // pool
                    // get.

                    dispatchers.put(name, dispatcherClass);

                }
            }
        }
    }
}