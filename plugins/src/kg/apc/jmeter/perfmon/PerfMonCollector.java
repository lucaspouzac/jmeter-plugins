package kg.apc.jmeter.perfmon;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

/**
 *
 * @author APC
 */
public class PerfMonCollector
        extends ResultCollector
        implements Runnable {

    private static boolean translateHostName = false;
    private static final long MEGABYTE = 1024L * 1024L;
    private static final String PERFMON = "PerfMon";
    private static final Logger log = LoggingManager.getLoggerForClass();
    public static final String DATA_PROPERTY = "metricConnections";
    private int interval;
    private Thread workerThread;
    private AgentConnector[] connectors = null;
    private HashMap<String, Long> oldValues = new HashMap<String, Long>();

    static {
        String cfgTranslateHostName = JMeterUtils.getProperty("jmeterPlugin.perfmon.translateHostName");
        if (cfgTranslateHostName != null) {
            translateHostName = "true".equalsIgnoreCase(cfgTranslateHostName.trim());
        }
    }

    public PerfMonCollector() {
        // TODO: document it
        interval = JMeterUtils.getPropDefault("jmeterPlugin.perfmon.interval", 1000);
    }

    public void setData(CollectionProperty rows) {
        setProperty(rows);
    }

    public JMeterProperty getData() {
        return getProperty(DATA_PROPERTY);
    }

    @Override
    public void sampleOccurred(SampleEvent event) {
        // just dropping regular test samples
    }

    @Override
    public synchronized void run() {
        while (true) {
            processConnectors();
            try {
                this.wait(interval);
            } catch (InterruptedException ex) {
                log.debug("Monitoring thread was interrupted", ex);
                break;
            }
        }
    }

    private void informUser(String msg) {
       log.info(msg);
       System.out.println(msg);
    }

    @Override
    public void testStarted(String host) {
        //if we run in non gui mode, ensure the data will be saved
        if(GuiPackage.getInstance() == null) {
           if(getProperty(FILENAME) == null || getProperty(FILENAME).getStringValue().trim().length() == 0) {
              setupSaving();
           } else {
              informUser("INFO: PerfMon metrics will be stored in: " + getProperty(FILENAME));
           }
        }

        initiateConnectors();

        workerThread = new Thread(this);
        workerThread.start();

        super.testStarted(host);
    }

    private void setupSaving() {
       Calendar now = Calendar.getInstance();
       SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd-HHmmss");
       String fileName = "perfMon_" + formatter.format(now.getTime()) + ".csv";
       setFilename(fileName);
       informUser("INFO: PerfMon metrics will be stored in: " + fileName);
    }

    @Override
    public void testEnded(String host) {
        workerThread.interrupt();
        shutdownConnectors();

        super.testEnded(host);
    }

    private void initiateConnectors() {
        oldValues.clear();
        JMeterProperty prop = getData();
        connectors = new AgentConnector[0];
        if (!(prop instanceof CollectionProperty)) {
            log.warn("Got unexpected property: " + prop);
            return;
        }
        CollectionProperty rows = (CollectionProperty) prop;

        connectors = new AgentConnector[rows.size()];

        for (int i = 0; i < connectors.length; i++) {
            ArrayList<Object> row = (ArrayList<Object>) rows.get(i).getObjectValue();
            String host = ((JMeterProperty) row.get(0)).getStringValue();
            int port = ((JMeterProperty) row.get(1)).getIntValue();
            String metric = ((JMeterProperty) row.get(2)).getStringValue();

            AgentConnector connector = new AgentConnector(host, port);
            connector.setMetricType(metric);

            try {
                Socket sock = createSocket(connector.getHost(), connector.getPort());
                connector.connect(sock);
                connectors[i] = connector;
            } catch (UnknownHostException e) {
                String msg = "Unknown host exception occured. Please verify access to the server '" + connector.getHost() + "'. (required for " + AgentConnector.metrics.get(connector.getMetricType()) + ")";
                log.error(msg, e);
                generateErrorSample("Agent Connnection", msg);
                connectors[i] = null;
            } catch (IOException e) {
                String msg = "Unable to connect to server '" + connector.getHost() + "'. Please verify the agent is running on port " + connector.getPort() + ". (required for " + AgentConnector.metrics.get(connector.getMetricType()) + ")";
                log.error(msg, e);
                generateErrorSample("Agent Connnection", msg);
                connectors[i] = null;
            } catch (PerfMonException e) {
                log.error("Agent Connnection", e);
                generateErrorSample("Agent Connnection", e.getMessage());
                connectors[i] = null;
            }
        }
    }

    private void shutdownConnectors() {
        for (int i = 0; i < connectors.length; i++) {
            if (connectors[i] != null) {
                connectors[i].disconnect();
            }
        }
    }

    protected Socket createSocket(String host, int port) throws UnknownHostException, IOException {
        return new Socket(host, port);
    }

    private void processConnectors() {
        String label;
        for (int i = 0; i < connectors.length; i++) {
            if (connectors[i] != null) {
                String hostName;
                if (PerfMonCollector.translateHostName) {
                    hostName = connectors[i].getRemoteServerName();
                } else {
                    hostName = connectors[i].getHost();
                }
                label = hostName + " - " + AgentConnector.metrics.get(connectors[i].getMetricType());

                try {
                    switch (connectors[i].getMetricType()) {
                        case AgentConnector.PERFMON_CPU:
                            generateSample(100 * connectors[i].getCpu(), label + ", %");
                            break;
                        case AgentConnector.PERFMON_MEM:
                            generateSample((double) connectors[i].getMem() / MEGABYTE, label + ", MB");
                            break;
                        case AgentConnector.PERFMON_SWAP:
                            generate2Samples(connectors[i].getSwap(), label + " page in", label + " page out");
                            break;
                        case AgentConnector.PERFMON_DISKS_IO:
                            generate2Samples(connectors[i].getDisksIO(), label + " reads", label + " writes");
                            break;
                        case AgentConnector.PERFMON_NETWORKS_IO:
                            generate2Samples(connectors[i].getNetIO(), label + " recv, KB", label + " sent, KB", 1024d);
                            break;
                        default:
                            log.error("Unknown metric index: " + connectors[i].getMetricType());
                    }
                } catch (PerfMonException e) {
                    generateErrorSample(label, e.getMessage() + " (while getting " + label + ")");
                    log.error(e.getMessage());
                    connectors[i] = null;
                }
            }
        }
    }

    //need floating point precision for memory and cpu
    private void generateSample(double value, String label) {
        if (value != AgentConnector.AGENT_ERROR) {
            PerfMonSampleResult res = new PerfMonSampleResult();
            res.setSampleLabel(label);
            res.setValue(value);
            res.setSuccessful(true);
            SampleEvent e = new SampleEvent(res, PERFMON);
            super.sampleOccurred(e);
        }
    }

    private void generateErrorSample(String label, String errorMsg) {
        PerfMonSampleResult res = new PerfMonSampleResult();
        res.setSampleLabel(label);
        res.setValue(-1L);
        res.setResponseMessage(errorMsg);
        res.setSuccessful(false);
        SampleEvent e = new SampleEvent(res, PERFMON);
        super.sampleOccurred(e);
        //add a console message for imediate user notice
        System.out.println("Perfmon plugin error: " + errorMsg);
    }

    private void generate2Samples(long[] values, String label1, String label2) {
        generate2Samples(values, label1, label2, 1d);
    }

    //float precision required for net io
    private void generate2Samples(long[] values, String label1, String label2, double dividingFactor) {
        if (oldValues.containsKey(label1) && oldValues.containsKey(label2)) {
            generateSample(((double) (values[0] - oldValues.get(label1).longValue())) / dividingFactor, label1);
            generateSample(((double) (values[1] - oldValues.get(label2).longValue())) / dividingFactor, label2);
        }
        oldValues.put(label1, new Long(values[0]));
        oldValues.put(label2, new Long(values[1]));
    }
}