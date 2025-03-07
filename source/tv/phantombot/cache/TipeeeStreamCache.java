/*
 * Copyright (C) 2016-2022 phantombot.github.io/PhantomBot
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package tv.phantombot.cache;

import com.scaniatv.TipeeeStreamAPIv1;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import tv.phantombot.PhantomBot;
import tv.phantombot.event.EventBus;
import tv.phantombot.event.tipeeestream.donate.TipeeeStreamDonationEvent;
import tv.phantombot.event.tipeeestream.donate.TipeeeStreamDonationInitializedEvent;

public class TipeeeStreamCache implements Runnable {

    private static final Map<String, TipeeeStreamCache> instances = new ConcurrentHashMap<>();
    private final Thread updateThread;
    private final String channel;
    private Map<String, String> cache = new ConcurrentHashMap<>();
    private Date timeoutExpire = new Date();
    private Date lastFail = new Date();
    private boolean firstUpdate = true;
    private boolean killed = false;
    private int numfail = 0;

    /**
     * Used to call and start this instance.
     *
     * @param {String} channel Channel to run the cache for.
     */
    public static TipeeeStreamCache instance(String channel) {
        TipeeeStreamCache instance = instances.get(channel);

        if (instance == null) {
            instance = new TipeeeStreamCache(channel);
            instances.put(channel, instance);
        }
        return instance;
    }

    /**
     * Starts this class on a new thread.
     *
     * @param {String} channel Channel to run the cache for.
     */
    private TipeeeStreamCache(String channel) {
        this.channel = channel;
        this.updateThread = new Thread(this, "tv.phantombot.cache.TipeeeStreamCache");

        Thread.setDefaultUncaughtExceptionHandler(com.gmt2001.UncaughtExceptionHandler.instance());
        this.updateThread.setUncaughtExceptionHandler(com.gmt2001.UncaughtExceptionHandler.instance());

        this.updateThread.start();
    }

    /**
     * Checks if the donation has been cached.
     *
     * @return {boolean}
     */
    public boolean exists(String donationID) {
        return cache.containsKey(donationID);
    }

    /**
     * Returns the current cache count (size/length),
     *
     * @return {Integer}
     */
    public int count() {
        return cache.size();
    }

    /**
     * Checks the amount of time we failed when calling the api to avoid abusing it.
     */
    private void checkLastFail() {
        Calendar cal = Calendar.getInstance();
        numfail = (lastFail.after(new Date()) ? numfail + 1 : 1);

        cal.add(Calendar.MINUTE, 1);
        lastFail = cal.getTime();

        if (numfail > 5) {
            timeoutExpire = cal.getTime();
        }
    }

    /**
     * Starts the cache loop.
     */
    @Override
    @SuppressWarnings("SleepWhileInLoop")
    public void run() {
        try {
            Thread.sleep(20 * 1000);
        } catch (InterruptedException ex) {
            com.gmt2001.Console.debug.println("TipeeeStreamCache.run: Failed to execute initial sleep [InterruptedException]: " + ex.getMessage());
        }

        while (!killed) {
            try {
                if (new Date().after(timeoutExpire)) {
                    this.updateCache();
                }
            } catch (Exception ex) {
                checkLastFail();
                com.gmt2001.Console.err.printStackTrace(ex);
            }

            try {
                Thread.sleep(30 * 1000);
            } catch (InterruptedException ex) {
                com.gmt2001.Console.debug.println("TipeeeStreamCache.run: Failed to sleep [InterruptedException]: " + ex.getMessage());
            }
        }
    }

    /**
     * Updates the cache by calling the TipeeeStream API.
     */
    private void updateCache() throws Exception {
        Map<String, String> newCache = new ConcurrentHashMap<>();
        JSONObject jsonResult;
        JSONObject object;
        JSONArray donations = null;

        com.gmt2001.Console.debug.println("TipeeeStreamCache::updateCache");

        jsonResult = TipeeeStreamAPIv1.instance().GetDonations();

        if (jsonResult.getBoolean("_success")) {
            if (jsonResult.getInt("_http") == 200) {
                if (jsonResult.has("datas")) {
                    object = jsonResult.getJSONObject("datas");
                    if (object.has("items")) {
                        donations = object.getJSONArray("items");
                        for (int i = 0; i < donations.length(); i++) {
                            newCache.put(donations.getJSONObject(i).get("id").toString(), donations.getJSONObject(i).get("id").toString());
                        }
                    }
                }
            } else {
                try {
                    throw new Exception("[HTTPErrorExecption] HTTP " + " " + jsonResult.getInt("_http") + ". req="
                            + jsonResult.getString("_type") + " " + jsonResult.getString("_url") + "   "
                            + (jsonResult.has("message") && !jsonResult.isNull("message") ? "message="
                            + jsonResult.getString("message") : "content=" + jsonResult.getString("_content")));
                } catch (Exception ex) {
                    /* Kill this cache if the tipeeestream token is bad and disable the module. */
                    if (ex.getMessage().contains("authentification")) {
                        com.gmt2001.Console.err.println("TipeeeStreamCache.updateCache: Bad API key disabling the TipeeeStream module.");
                        PhantomBot.instance().getDataStore().SetString("modules", "", "./handlers/tipeeestreamHandler.js", "false");
                    } else {
                        com.gmt2001.Console.err.printStackTrace(ex);
                    }
                    this.kill();
                }
            }
        } else {
            try {
                throw new Exception("[" + jsonResult.getString("_exception") + "] " + jsonResult.getString("_exceptionMessage"));
            } catch (Exception ex) {
                if (ex.getMessage().startsWith("[SocketTimeoutException]") || ex.getMessage().startsWith("[IOException]")) {
                    checkLastFail();
                    com.gmt2001.Console.err.printStackTrace(ex);
                }
            }
        }

        if (firstUpdate && !killed) {
            firstUpdate = false;
            EventBus.instance().postAsync(new TipeeeStreamDonationInitializedEvent());
        }

        if (donations != null && !killed) {
            for (int i = 0; i < donations.length(); i++) {
                if ((cache == null || !cache.containsKey(donations.getJSONObject(i).get("id").toString()))
                        && !PhantomBot.instance().getDataStore().exists("donations", donations.getJSONObject(i).get("id").toString())) {
                    EventBus.instance().postAsync(new TipeeeStreamDonationEvent(donations.getJSONObject(i).toString()));
                }
            }
        }
        this.cache = newCache;
    }

    /**
     * Sets the current cache.
     *
     * @param {Map} Cache
     */
    public void setCache(Map<String, String> cache) {
        this.cache = cache;
    }

    /**
     * Returns the current cache.
     *
     * @return {Map} Current cache.
     */
    public Map<String, String> getCache() {
        return cache;
    }

    /**
     * Kills the current cache.
     */
    public void kill() {
        killed = true;
    }

    /**
     * Kills all the caches.
     */
    public static void killall() {
        for (Entry<String, TipeeeStreamCache> instance : instances.entrySet()) {
            instance.getValue().kill();
        }
    }
}
