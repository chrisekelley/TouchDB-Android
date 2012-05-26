package com.couchbase.touchdb.replicator.changetracker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.codehaus.jackson.map.ObjectMapper;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.couchbase.touchdb.TDBody;
import com.couchbase.touchdb.TDDatabase;
import com.couchbase.touchdb.TDServer;
import com.couchbase.touchdb.router.TDRouter;
import com.couchbase.touchdb.router.TDURLConnection;

/**
 * Reads the continuous-mode _changes feed of a database, and sends the
 * individual change entries to its client's changeTrackerReceivedChange()
 */
public class TDChangeTracker implements Runnable {

    private ObjectMapper mapper = new ObjectMapper();

    private URL databaseURL;
    private TDChangeTrackerClient client;
    private TDChangeTrackerMode mode;
    private Object lastSequenceID;

    private HandlerThread handlerThread;
    private Handler handler;
    private Thread thread;
    private boolean running = false;
    private HttpUriRequest request;

    private String filterName;
    private Map<String, Object> filterParams;
    private TDServer server;
    
    public static final String TAG = "TDChangeTracker";

    public enum TDChangeTrackerMode {
        OneShot, LongPoll, Continuous
    }

    public TDChangeTracker(URL databaseURL, TDChangeTrackerMode mode,
            Object lastSequenceID, TDChangeTrackerClient client) {
        //first start a handler thread
        String threadName = Thread.currentThread().getName();
        handlerThread = new HandlerThread("ChangeTracker HandlerThread for " + threadName);
        handlerThread.start();
        //Get the looper from the handlerThread
        Looper looper = handlerThread.getLooper();
        //Create a new handler - passing in the looper for it to use
        this.handler = new Handler(looper);
        this.databaseURL = databaseURL;
        this.mode = mode;
        this.lastSequenceID = lastSequenceID;
        this.client = client;
    }

    public void setFilterName(String filterName) {
        this.filterName = filterName;
    }

    public void setFilterParams(Map<String, Object> filterParams) {
        this.filterParams = filterParams;
    }

    public void setClient(TDChangeTrackerClient client) {
        this.client = client;
    }

    public String getDatabaseName() {
        String result = null;
        if (databaseURL != null) {
            result = databaseURL.getPath();
            if (result != null) {
                int pathLastSlashPos = result.lastIndexOf('/');
                if (pathLastSlashPos > 0) {
                    result = result.substring(pathLastSlashPos);
                }
            }
        }
        return result;
    }

    public String getChangesFeedPath() {
        String path = "_changes?feed=";
        switch (mode) {
        case OneShot:
            path += "normal";
            break;
        case LongPoll:
            path += "longpoll";
            break;
        case Continuous:
            path += "continuous";
            break;
        }
        path += "&heartbeat=300000";

        if(lastSequenceID != null) {
            path += "&since=" + URLEncoder.encode(lastSequenceID.toString());
        }
        if(filterName != null) {
            path += "&filter=" + URLEncoder.encode(filterName);
            if(filterParams != null) {
                for (String filterParamKey : filterParams.keySet()) {
                    path += "&" + URLEncoder.encode(filterParamKey) + "=" + URLEncoder.encode(filterParams.get(filterParamKey).toString());
                }
            }
        }

        return path;
    }

    public URL getChangesFeedURL() {
        String dbURLString = databaseURL.toExternalForm();
        if(!dbURLString.endsWith("/")) {
            dbURLString += "/";
        }
        dbURLString += getChangesFeedPath();
        URL result = null;
        try {
            result = new URL(dbURLString);
        } catch(MalformedURLException e) {
            Log.e(TDDatabase.TAG, "Changes feed ULR is malformed", e);
        }
        return result;
    }

    @Override
    public void run() {
        running = true;
        HttpClient httpClient = client.getHttpClient();
        while (running) {
            request = new HttpGet(getChangesFeedURL().toString());
            try {
            	StatusLine status = null;
            	HttpEntity entity = null;
            	int statucCode = 0;
            	InputStream input = null;
                Log.v(TDDatabase.TAG, "Making request to " + getChangesFeedURL().toString());
                if (getChangesFeedURL().toString().startsWith("touchdb")) {
                	//TDURLConnection conn = (TDURLConnection)getChangesFeedURL().openConnection();
                    TDURLConnection conn = sendRequest(server, "GET", getChangesFeedPath(), null, null);
                    statucCode = conn.getResponseCode();
                    TDBody fullBody = conn.getResponseBody();
                    boolean responseOK = receivedPollResponse(fullBody.getProperties());
                    if(mode == TDChangeTrackerMode.LongPoll && responseOK) {
                        Log.v(TDDatabase.TAG, "Starting new longpoll");
                        continue;
                    } else {
                        Log.w(TDDatabase.TAG, "Change tracker calling stop");
                        stop();
                    }
                } else {
                	HttpResponse response = httpClient.execute(request);
                    status = response.getStatusLine();
                    entity = response.getEntity();
                    input = entity.getContent();
                    statucCode = status.getStatusCode();
                    
                    if(input != null) {
                    	try {
    	                    if(mode != TDChangeTrackerMode.Continuous) {
    	                        Map<String,Object> fullBody = mapper.readValue(input, Map.class);
    	                        boolean responseOK = receivedPollResponse(fullBody);
    	                        if(mode == TDChangeTrackerMode.LongPoll && responseOK) {
    	                            Log.v(TDDatabase.TAG, "Starting new longpoll");
    	                            continue;
    	                        } else {
    	                            Log.w(TDDatabase.TAG, "Change tracker calling stop");
    	                            stop();
    	                        }
    	                    }
    	                    else {
    	                        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
    	                        String line = null;
    	                        while ((line=reader.readLine()) != null) {
    	                            receivedChunk(line);
    	                        }
    	                    }
                    	} finally {
    						try {
    							//entity.consumeContent();
    							if (entity != null) {
    								entity.consumeContent();
    							}
    							
    						} catch (IOException e) {
    						}
                    	}
                    }
                }
                if(statucCode >= 300) {
                    Log.e(TDDatabase.TAG, "Change tracker got error " + Integer.toString(status.getStatusCode()));
                    stop();
                }

            } catch (ClientProtocolException e) {
                Log.e(TDDatabase.TAG, "ClientProtocolException in change tracker", e);
            } catch (IOException e) {
                if(running) {
                    //we get an exception when we're shutting down and have to
                    //close the socket underneath our read, ignore that
                    Log.e(TDDatabase.TAG, "IOException in change tracker", e);
                }
            }
        }
        Log.v(TDDatabase.TAG, "Chagne tracker run loop exiting");
    }

    public void receivedChunk(String line) {
        if(line.length() <= 1) {
            return;
        }
        try {
            Map<String,Object> change = (Map)mapper.readValue(line, Map.class);
            receivedChange(change);
        } catch (Exception e) {
            Log.w(TDDatabase.TAG, "Exception parsing JSON in change tracker", e);
        }
    }

    public boolean receivedChange(final Map<String,Object> change) {
        Object seq = change.get("seq");
        if(seq == null) {
            return false;
        }
        //pass the change to the client on the thread that created this change tracker
        handler.post(new Runnable() {

            TDChangeTrackerClient copy = client;

            @Override
            public void run() {
                if(copy == null) {
                    Log.v(TDDatabase.TAG, "cannot notify client, client is null");
                } else {
                    Log.v(TDDatabase.TAG, "about to notify client");
                    copy.changeTrackerReceivedChange(change);
                }
            }
        });
        lastSequenceID = seq;
        return true;
    }

    public boolean receivedPollResponse(Map<String,Object> response) {
        List<Map<String,Object>> changes = (List)response.get("results");
        if(changes == null) {
            return false;
        }
        for (Map<String,Object> change : changes) {
            if(!receivedChange(change)) {
                return false;
            }
        }
        return true;
    }

    public boolean start() {
        thread = new Thread(this);
        thread.start();
        return true;
    }

    public void stop() {
        running = false;
        thread.interrupt();
        if(request != null) {
            request.abort();
        }

        stopped();
    }

    public void stopped() {
        Log.d(TDDatabase.TAG, "in stopped");
        if (client != null  && handler != null) {
            Log.d(TDDatabase.TAG, "posting stopped");
            handler.post(new Runnable() {

                TDChangeTrackerClient copy = client;

                @Override
                public void run() {
                    copy.changeTrackerStopped(TDChangeTracker.this);

                    //Shut down the HandlerThread
                    handlerThread.quit();
                    handlerThread = null;
                    handler = null;
                }
            });
        } else if(handler != null) {
            //Shut down the HandlerThread
            handlerThread.quit();
            handlerThread = null;
            handler = null;
        }
        client = null;
    }

    public boolean isRunning() {
        return running;
    }

	public TDServer getServer() {
		return server;
	}

	public void setServer(TDServer server) {
		this.server = server;
	}
	
	//private ObjectMapper mapper = new ObjectMapper();

    protected TDURLConnection sendRequest(TDServer server, String method, String path, Map<String,String> headers, Object bodyObj) {
        try {
            URL url = new URL("touchdb://" + path);
            TDURLConnection conn = (TDURLConnection)url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod(method);
            if(headers != null) {
                for (String header : headers.keySet()) {
                    conn.setRequestProperty(header, headers.get(header));
                }
            }
            Map<String, List<String>> allProperties = conn.getRequestProperties();
            if(bodyObj != null) {
                conn.setDoInput(true);
                OutputStream os = conn.getOutputStream();
                os.write(mapper.writeValueAsBytes(bodyObj));
            }

            TDRouter router = new TDRouter(server, conn);
            router.start();
            return conn;
        } catch (MalformedURLException e) {
        	Log.e(TAG, "Bad URL: ", e);
        } catch(IOException e) {
        	Log.e(TAG, "I/O Exception: ", e);
        }
        return null;
    }

}
