/*
 * Copyright 2015 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.data;

import android.content.Context;
import android.os.HandlerThread;
import android.util.Log;

import com.google.gson.JsonObject;

import org.matrix.androidsdk.HomeserverConnectionConfig;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.TokensChunkResponse;
import org.matrix.androidsdk.util.ContentUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * An in-file IMXStore.
 */
public class MXFileStore extends MXMemoryStore {
    private static final String LOG_TAG = "MXFileStore";

    // some constant values
    final int MXFILE_VERSION = 1;

    // ensure that there is enough messages to fill a tablet screen
    final int MAX_STORED_MESSAGES_COUNT = 50;

    final String MXFILE_STORE_FOLDER = "MXFileStore";
    final String MXFILE_STORE_METADATA_FILE_NAME = "MXFileStore";

    final String MXFILE_STORE_ROOMS_MESSAGES_FOLDER = "messages";
    final String MXFILE_STORE_GZ_ROOMS_MESSAGES_FOLDER = "messages_gz";
    final String MXFILE_STORE_ROOMS_TOKENS_FOLDER = "tokens";
    final String MXFILE_STORE_ROOMS_STATE_FOLDER = "state";
    final String MXFILE_STORE_GZ_ROOMS_STATE_FOLDER = "state_gz";
    final String MXFILE_STORE_ROOMS_SUMMARY_FOLDER = "summary";

    private Context mContext = null;

    // the data is read from the file system
    private boolean mIsReady = false;

    // the store is currently opening
    private boolean mIsOpening = false;

    private MXStoreListener mListener = null;

    // List of rooms to save on [MXStore commit]
    private ArrayList<String> mRoomsToCommitForMessages;

    private ArrayList<String> mRoomsToCommitForStates;
    private ArrayList<String> mRoomsToCommitForSummaries;

    // Flag to indicate metaData needs to be store
    private boolean mMetaDataHasChanged = false;

    // The path of the MXFileStore folders
    private File mStoreFolderFile = null;
    private File mOldStoreRoomsMessagesFolderFile = null;
    private File mGzStoreRoomsMessagesFolderFile = null;
    private File mStoreRoomsTokensFolderFile = null;
    private File mOldStoreRoomsStateFolderFile = null;
    private File mGzStoreRoomsStateFolderFile = null;
    private File mStoreRoomsSummaryFolderFile = null;

    // the background thread
    private HandlerThread mHandlerThread = null;
    private android.os.Handler mFileStoreHandler = null;

    private Boolean mIsKilled = false;

    private Boolean mIsNewStorage = false;

    /**
     * Create the file store dirtrees
     */
    private void createDirTree(String userId) {
        // data path
        // MXFileStore/userID/
        // MXFileStore/userID/MXFileStore
        // MXFileStore/userID/Messages/
        // MXFileStore/userID/Tokens/
        // MXFileStore/userID/States/
        // MXFileStore/userID/Summaries/

        // create the dirtree
        mStoreFolderFile = new File(new File(mContext.getApplicationContext().getFilesDir(), MXFILE_STORE_FOLDER), userId);

        if (!mStoreFolderFile.exists()) {
            mStoreFolderFile.mkdirs();
        }

        mOldStoreRoomsMessagesFolderFile = new File(mStoreFolderFile, MXFILE_STORE_ROOMS_MESSAGES_FOLDER);

        mGzStoreRoomsMessagesFolderFile = new File(mStoreFolderFile, MXFILE_STORE_GZ_ROOMS_MESSAGES_FOLDER);
        if (!mGzStoreRoomsMessagesFolderFile.exists()) {
            mGzStoreRoomsMessagesFolderFile.mkdirs();
        }

        mStoreRoomsTokensFolderFile = new File(mStoreFolderFile, MXFILE_STORE_ROOMS_TOKENS_FOLDER);
        if (!mStoreRoomsTokensFolderFile.exists()) {
            mStoreRoomsTokensFolderFile.mkdirs();
        }

        mOldStoreRoomsStateFolderFile = new File(mStoreFolderFile, MXFILE_STORE_ROOMS_STATE_FOLDER);

        mGzStoreRoomsStateFolderFile = new File(mStoreFolderFile, MXFILE_STORE_GZ_ROOMS_STATE_FOLDER);
        if (!mGzStoreRoomsStateFolderFile.exists()) {
            mGzStoreRoomsStateFolderFile.mkdirs();
        }

        mStoreRoomsSummaryFolderFile = new File(mStoreFolderFile, MXFILE_STORE_ROOMS_SUMMARY_FOLDER);
        if (!mStoreRoomsSummaryFolderFile.exists()) {
            mStoreRoomsSummaryFolderFile.mkdirs();
        }
    }

    /**
     * Default constructor
     * @param hsConfig the expected credentials
     */
    public MXFileStore(HomeserverConnectionConfig hsConfig, Context context) {
        initCommon();
        mContext = context;
        mIsReady = false;
        mCredentials = hsConfig.getCredentials();

        mHandlerThread = new HandlerThread("MXFileStoreBackgroundThread_" + mCredentials.userId, Thread.MIN_PRIORITY);

        createDirTree(mCredentials.userId);

        // updated data
        mRoomsToCommitForMessages = new ArrayList<String>();
        mRoomsToCommitForStates = new ArrayList<String>();
        mRoomsToCommitForSummaries = new ArrayList<String>();

        // check if the metadata file exists and if it is valid
        loadMetaData();

        if ( (null == mMetadata) ||
                (mMetadata.mVersion != MXFILE_VERSION) ||
                !mMetadata.mUserId.equals(mCredentials.userId) ||
                !mMetadata.mAccessToken.equals(mCredentials.accessToken)) {
            deleteAllData(true);
        }

        // create the medatata file if it does not exist
        if (null == mMetadata) {
            mIsNewStorage = true;
            mIsOpening = true;
            mHandlerThread.start();
            mFileStoreHandler = new android.os.Handler(mHandlerThread.getLooper());

            mMetadata = new MXFileStoreMetaData();
            mMetadata.mUserId = mCredentials.userId;
            mMetadata.mAccessToken = mCredentials.accessToken;
            mMetadata.mVersion = MXFILE_VERSION;
            mMetaDataHasChanged = true;
            saveMetaData();

            mEventStreamToken = null;

            mIsOpening = false;
            // nothing to load so ready to work
            mIsReady = true;
        }
    }

    /**
     * Killed the background thread.
     * @param isKilled
     */
    private void setIsKilled(Boolean isKilled) {
        synchronized (this) {
            mIsKilled = isKilled;
        }
    }

    /**
     * @return true if the background thread is killed.
     */
    private Boolean isKilled() {
        Boolean isKilled;

        synchronized (this) {
            isKilled = mIsKilled;
        }

        return isKilled;
    }

    /**
     * Save changes in the store.
     * If the store uses permanent storage like database or file, it is the optimised time
     * to commit the last changes.
     */
    @Override
    public void commit() {
        // Save data only if metaData exists
        if ((null != mMetadata) && !isKilled()) {
            Log.d(LOG_TAG, "++ Commit");
            saveRoomsMessages();
            saveRoomStates();
            saveMetaData();
            saveSummaries();
            Log.d(LOG_TAG, "-- Commit");
        }
    }

    /**
     * Open the store.
     */
    public void open() {
        super.open();

        // avoid concurrency call.
        synchronized (this) {
            if (!mIsReady && !mIsOpening && (null != mMetadata) && (null != mHandlerThread)) {
                mIsOpening = true;

                Log.e(LOG_TAG, "Open the store.");

                // creation the background handler.
                if (null == mFileStoreHandler) {
                    // avoid already started exception
                    // never succeeded to reproduce but it was reported in GA.
                    try {
                        mHandlerThread.start();
                    } catch (IllegalThreadStateException e) {
                        Log.e(LOG_TAG, "mHandlerThread is already started.");
                        // already started
                        return;
                    }
                    mFileStoreHandler = new android.os.Handler(mHandlerThread.getLooper());
                }

                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        mFileStoreHandler.post(new Runnable() {
                            public void run() {
                                Log.e(LOG_TAG, "Open the store in the background thread.");

                                boolean succeed = true;

                                succeed &= loadRoomsMessages();

                                if (!succeed) {
                                    Log.e(LOG_TAG, "loadRoomsMessages fails");
                                } else {
                                    Log.e(LOG_TAG, "loadRoomsMessages succeeds");
                                }

                                if (succeed) {
                                    succeed &= loadRoomsState();

                                    if (!succeed) {
                                        Log.e(LOG_TAG, "loadRoomsState fails");
                                    } else {
                                        Log.e(LOG_TAG, "loadRoomsState succeeds");
                                    }
                                }

                                if (succeed) {
                                    succeed &= loadSummaries();

                                    if (!succeed) {
                                        Log.e(LOG_TAG, "loadSummaries fails");
                                    } else {
                                        Log.e(LOG_TAG, "loadSummaries succeeds");
                                    }
                                }

                                // do not expect having empty list
                                // assume that something is corrupted
                                if (!succeed) {

                                    Log.e(LOG_TAG, "Fail to open the store in background");

                                    deleteAllData(true);

                                    mRoomsToCommitForMessages = new ArrayList<String>();
                                    mRoomsToCommitForStates = new ArrayList<String>();
                                    mRoomsToCommitForSummaries = new ArrayList<String>();

                                    mMetadata = new MXFileStoreMetaData();
                                    mMetadata.mUserId = mCredentials.userId;
                                    mMetadata.mAccessToken = mCredentials.accessToken;
                                    mMetadata.mVersion = MXFILE_VERSION;
                                    mMetaDataHasChanged = true;
                                    saveMetaData();

                                    mEventStreamToken = null;
                                }

                                synchronized (this) {
                                    mIsReady = true;
                                }
                                mIsOpening = false;

                                if (null != mListener) {
                                    if (!succeed && !mIsNewStorage) {
                                        Log.e(LOG_TAG, "The store is corrupted.");
                                        mListener.onStoreCorrupted(mCredentials.userId);
                                    } else {
                                        Log.e(LOG_TAG, "The store is opened.");
                                        mListener.onStoreReady(mCredentials.userId);
                                    }
                                }
                            }
                        });
                    }
                };

                Thread t = new Thread(r);
                t.start();
            }
        }
    }

    /**
     * Close the store.
     * Any pending operation must be complete in this call.
     */
    @Override
    public void close() {
        Log.d(LOG_TAG, "Close the store");

        super.close();
        setIsKilled(true);
        mHandlerThread.quit();
        mHandlerThread = null;
    }

    /**
     * Clear the store.
     * Any pending operation must be complete in this call.
     */
    @Override
    public void clear() {
        Log.d(LOG_TAG, "Clear the store");
        super.close();
        deleteAllData(false);
    }

    /**
     * Clear the filesystem storage.
     * @param init true to init the filesystem dirtree
     */
    private void deleteAllData(boolean init)
    {
        // delete the dedicated directories
        try {
            ContentUtils.deleteDirectory(mStoreFolderFile);
            if (init) {
                createDirTree(mCredentials.userId);
            }
        } catch(Exception e) {
        }

        if (init) {
            initCommon();
        }
        mMetadata = null;
        mEventStreamToken = null;
    }

    /**
     * Indicate if the MXStore implementation stores data permanently.
     * Permanent storage allows the SDK to make less requests at the startup.
     * @return true if permanent.
     */
    @Override
    public boolean isPermanent() {
        return true;
    }

    /**
     * Check if the initial load is performed.
     * @return true if it is ready.
     */
    @Override
    public boolean isReady() {
        synchronized (this) {
            return mIsReady;
        }
    }

    /**
     * Delete a directory with its content
     * @param directory the base directory
     * @return
     */
    private long directorySize(File directory) {
        long directorySize = 0;

        if (directory.exists()) {
            File[] files = directory.listFiles();

            if (null != files) {
                for(int i=0; i<files.length; i++) {
                    if(files[i].isDirectory()) {
                        directorySize += directorySize(files[i]);
                    }
                    else {
                        directorySize += files[i].length();
                    }
                }
            }
        }

        return directorySize;
    }

    /**
     * Returns to disk usage size in bytes.
     * @return disk usage size
     */
    @Override
    public long diskUsage() {
        return directorySize(mStoreFolderFile);
    }

    /**
     * Set the event stream token.
     * @param token the event stream token
     */
    @Override
    public void setEventStreamToken(String token) {
        Log.d(LOG_TAG, "Set token to " + token);
        super.setEventStreamToken(token);
        mMetaDataHasChanged = true;
    }

    @Override
    public void setDisplayName(String displayName) {
        Log.d(LOG_TAG, "Set setDisplayName to " + displayName);
        mMetaDataHasChanged = true;
        super.setDisplayName(displayName);
    }

    @Override
    public void setAvatarURL(String avatarURL) {
        Log.d(LOG_TAG, "Set setAvatarURL to " + avatarURL);
        mMetaDataHasChanged = true;
        super.setAvatarURL(avatarURL);
    }

    /**
     * Define a MXStore listener.
     * @param listener
     */
    @Override
    public void setMXStoreListener(MXStoreListener listener) {
        mListener = listener;
    }

    @Override
    public void storeRoomEvents(String roomId, TokensChunkResponse<Event> eventsResponse, Room.EventDirection direction) {
        Boolean canStore = true;

        // do not flush the room messages file
        // when the user reads the room history and the events list size reaches its max size.
        if (direction == Room.EventDirection.BACKWARDS) {
            LinkedHashMap<String, Event> events = mRoomEvents.get(roomId);

            if (null != events) {
                canStore = (events.size() < MAX_STORED_MESSAGES_COUNT);

                if (!canStore) {
                    Log.d(LOG_TAG, "storeRoomEvents : do not flush because reaching the max size");
                }
            }
        }

        super.storeRoomEvents(roomId, eventsResponse, direction);

        if (canStore && (mRoomsToCommitForMessages.indexOf(roomId) < 0)) {
            mRoomsToCommitForMessages.add(roomId);
        }
    }

    /**
     * Store a live room event.
     * @param event The event to be stored.
     */
    @Override
    public void storeLiveRoomEvent(Event event) {
        super.storeLiveRoomEvent(event);

        if (mRoomsToCommitForMessages.indexOf(event.roomId) < 0) {
            mRoomsToCommitForMessages.add(event.roomId);
        }
    }

    @Override
    public boolean updateEventContent(String roomId, String eventId, JsonObject newContent) {
        Boolean isReplaced = super.updateEventContent(roomId, eventId, newContent);

        if (isReplaced) {
            if (mRoomsToCommitForMessages.indexOf(roomId) < 0) {
                mRoomsToCommitForMessages.add(roomId);
            }
        }

        return isReplaced;
    }

    @Override
    public void deleteEvent(Event event) {
        super.deleteEvent(event);

        if (mRoomsToCommitForMessages.indexOf(event.roomId) < 0) {
            mRoomsToCommitForMessages.add(event.roomId);
        }
    }

    /**
     * Delete the room messages and token files.
     * @param roomId the room id.
     */
    private void deleteRoomMessagesFiles(String roomId) {
        // messages list
        File messagesListFile = new File(mOldStoreRoomsMessagesFolderFile, roomId);

        // remove the files
        if (messagesListFile.exists()) {
            try {
                messagesListFile.delete();
            } catch (Exception e) {
            }
        }

        messagesListFile = new File(mGzStoreRoomsMessagesFolderFile, roomId);

        // remove the files
        if (messagesListFile.exists()) {
            try {
                messagesListFile.delete();
            } catch (Exception e) {
            }
        }

        File tokenFile = new File(mStoreRoomsTokensFolderFile, roomId);
        if (tokenFile.exists()) {
            try {
                tokenFile.delete();
            } catch (Exception e) {
            }
        }
    }

    @Override
    public void deleteRoom(String roomId) {
        Log.d(LOG_TAG, "deleteRoom " + roomId);

        super.deleteRoom(roomId);
        deleteRoomMessagesFiles(roomId);
        deleteRoomStateFile(roomId);
        deleteRoomSummaryFile(roomId);
    }

    @Override
    public void storeLiveStateForRoom(String roomId) {
        super.storeLiveStateForRoom(roomId);

        if (mRoomsToCommitForStates.indexOf(roomId) < 0) {
            mRoomsToCommitForStates.add(roomId);
        }
    }

    @Override
    public void flushSummary(RoomSummary summary) {
        super.flushSummary(summary);

        if (mRoomsToCommitForSummaries.indexOf(summary.getRoomId()) < 0) {
            mRoomsToCommitForSummaries.add(summary.getRoomId());
            saveSummaries();
        }
    }

    @Override
    public void flushSummaries() {
        super.flushSummaries();

        // add any existing roomid to the list to save all
        Collection<String> roomIds = mRoomSummaries.keySet();

        for(String roomId : roomIds) {
            if (mRoomsToCommitForSummaries.indexOf(roomId) < 0) {
                mRoomsToCommitForSummaries.add(roomId);
            }
        }

        saveSummaries();
    }

    @Override
    public void storeSummary(String matrixId, String roomId, Event event, RoomState roomState, String selfUserId) {
        super.storeSummary(matrixId, roomId, event, roomState, selfUserId);

        if (mRoomsToCommitForSummaries.indexOf(roomId) < 0) {
            mRoomsToCommitForSummaries.add(roomId);
        }
    }

    private void saveRoomMessages(String roomId) {
        try {
            deleteRoomMessagesFiles(roomId);

            // messages list
            File messagesListFile = new File(mGzStoreRoomsMessagesFolderFile, roomId);

            File tokenFile = new File(mStoreRoomsTokensFolderFile, roomId);

            LinkedHashMap<String, Event> eventsHash = mRoomEvents.get(roomId);
            String token = mRoomTokens.get(roomId);

            // the list exists ?
            if ((null != eventsHash) && (null != token)) {
                FileOutputStream fos = new FileOutputStream(messagesListFile);
                GZIPOutputStream gz = new GZIPOutputStream(fos);
                ObjectOutputStream out = new ObjectOutputStream(gz);

                LinkedHashMap<String, Event> hashCopy = new LinkedHashMap<String, Event>();
                ArrayList<Event> eventsList = new ArrayList<Event>(eventsHash.values());

                int startIndex = 0;

                // try to reduce the number of stored messages
                // it does not make sense to keep the full history.

                // the method consists in saving messages until finding the oldest known token.
                // At initial sync, it is not saved so keep the whole history.
                // if the user back paginates, the token is stored in the event.
                // if some messages are received, the token is stored in the event.
                if (eventsList.size() > MAX_STORED_MESSAGES_COUNT) {
                    startIndex = eventsList.size() - MAX_STORED_MESSAGES_COUNT;

                    // search backward the first known token
                    for (; !eventsList.get(startIndex).hasToken() && (startIndex > 0); startIndex--)
                        ;

                    // avoid saving huge messages count
                    // with a very verbosed room, the messages token
                    if ((eventsList.size() - startIndex) > (2 * MAX_STORED_MESSAGES_COUNT)) {
                        Log.d(LOG_TAG, "saveRoomsMessage (" + roomId + ") : too many messages, try reducing more");

                        // start from 10 messages
                        startIndex = eventsList.size() - 10;

                        // search backward the first known token
                        for (; !eventsList.get(startIndex).hasToken() && (startIndex > 0); startIndex--)
                            ;
                    }

                    if (startIndex > 0) {
                        Log.d(LOG_TAG, "saveRoomsMessage (" + roomId + ") :  reduce the number of messages " + eventsList.size() + " -> " + (eventsList.size() - startIndex));
                    }
                }

                long t0 = System.currentTimeMillis();

                for (int index = startIndex; index < eventsList.size(); index++) {
                    Event event = eventsList.get(index);
                    event.prepareSerialization();
                    hashCopy.put(event.eventId, event);
                }

                out.writeObject(hashCopy);
                out.close();

                fos = new FileOutputStream(tokenFile);
                out = new ObjectOutputStream(fos);
                out.writeObject(token);
                out.close();

                Log.d(LOG_TAG, "saveRoomsMessage (" + roomId + ") : " + eventsList.size() + " messages saved in " +  (System.currentTimeMillis() - t0) + " ms");
            }
        } catch (Exception e) {
        }
    }

    /**
     * Flush updates rooms messages list files.
     */
    private void saveRoomsMessages() {
        // some updated rooms ?
        if  ((mRoomsToCommitForMessages.size() > 0) && (null != mFileStoreHandler)) {
            // get the list
            final ArrayList<String> fRoomsToCommitForMessages = mRoomsToCommitForMessages;
            mRoomsToCommitForMessages = new ArrayList<String>();

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    mFileStoreHandler.post(new Runnable() {
                        public void run() {
                            if (!isKilled()) {
                                long start = System.currentTimeMillis();

                                for (String roomId : fRoomsToCommitForMessages) {
                                    saveRoomMessages(roomId);
                                }

                                Log.d(LOG_TAG, "saveRoomsMessages : " + fRoomsToCommitForMessages.size() + " rooms in " + (System.currentTimeMillis() - start) + " ms");
                            }
                        }
                    });
                }
            };

            Thread t = new Thread(r);
            t.start();
        }
    }

    /**
     * Load room messages from the filesystem.
     * @param roomId the room id.
     * @return true if succeed.
     */
    private boolean loadRoomMessages(final String roomId) {
        Boolean succeeded = true;
        Boolean shouldSave = false;
        LinkedHashMap<String, Event> events = null;

        try {
            File messagesListFile = new File(mGzStoreRoomsMessagesFolderFile, roomId);

            if (messagesListFile.exists()) {
                FileInputStream fis = new FileInputStream(messagesListFile);
                GZIPInputStream gz = new GZIPInputStream(fis);
                ObjectInputStream ois = new ObjectInputStream(gz);
                events = (LinkedHashMap<String, Event>) ois.readObject();

                for (Event event : events.values()) {
                    event.finalizeDeserialization();
                }

                ois.close();

                messagesListFile = new File(mOldStoreRoomsMessagesFolderFile, roomId);
                if (messagesListFile.exists()) {
                    messagesListFile.delete();
                }
            } else {
                messagesListFile = new File(mOldStoreRoomsMessagesFolderFile, roomId);

                if (messagesListFile.exists()) {

                    FileInputStream fis = new FileInputStream(messagesListFile);
                    ObjectInputStream ois = new ObjectInputStream(fis);
                    events = (LinkedHashMap<String, Event>) ois.readObject();

                    for (Event event : events.values()) {
                        event.finalizeDeserialization();
                    }

                    ois.close();

                    shouldSave = true;
                }
            }
        } catch (Exception e){
            succeeded = false;
            Log.e(LOG_TAG, "loadRoomMessages failed : " + e.getMessage());
        }

        // succeeds to extract the message list
        if (null != events) {
            // create the room object
            Room room = new Room();
            room.setRoomId(roomId);
            // do not wait that the live state update
            room.setReadyState(true);
            storeRoom(room);

            mRoomEvents.put(roomId, events);
        }

        if (shouldSave) {
            saveRoomMessages(roomId);
        }

        return succeeded;
    }

    /**
     * Load the room token from the file system.
     * @param roomId the room id.
     * @return true if it succeeds.
     */
    private Boolean loadRoomToken(final String roomId) {
        Boolean succeed = true;

        Room room = getRoom(roomId);

        // should always be true
        if (null != room) {
            String token = null;

            try {
                File messagesListFile = new File(mStoreRoomsTokensFolderFile, roomId);

                FileInputStream fis = new FileInputStream(messagesListFile);
                ObjectInputStream ois = new ObjectInputStream(fis);
                token = (String) ois.readObject();

                // check if the oldest event has a token.
                LinkedHashMap<String, Event> eventsHash = mRoomEvents.get(roomId);
                if ((null != eventsHash) && (eventsHash.size() > 0)) {
                    Event event = eventsHash.values().iterator().next();

                    // the room history could have been reduced to save memory
                    // so, if the oldest messages has a token, use it instead of the stored token.
                    if (null != event.mToken) {
                        token = event.mToken;
                    }
                }

                ois.close();
            } catch (Exception e) {
                succeed = false;
                Log.e(LOG_TAG, "loadRoomToken failed : " + e.getMessage());
            }

            if (null != token) {
                mRoomTokens.put(roomId, token);
            } else {
                deleteRoom(roomId);
            }
        } else {
            try {
                File messagesListFile = new File(mStoreRoomsTokensFolderFile, roomId);
                messagesListFile.delete();

            } catch (Exception e) {
            }
        }

        return succeed;
    }

    /**
     * Load room messages from the filesystem.
     * @return  true if the operation succeeds.
     */
    private boolean loadRoomsMessages() {
        Boolean succeed = true;

        try {
            // extract the messages list
            String[] filenames = mGzStoreRoomsMessagesFolderFile.list();

            long start = System.currentTimeMillis();

            for(int index = 0; succeed && (index < filenames.length); index++) {
                succeed &= loadRoomMessages(filenames[index]);
            }

            // convert old format to the new one.
            if (mOldStoreRoomsMessagesFolderFile.exists()) {
                filenames = mOldStoreRoomsMessagesFolderFile.list();

                for(int index = 0; succeed && (index < filenames.length); index++) {
                    succeed &= loadRoomMessages(filenames[index]);
                }
            }

            Log.d(LOG_TAG, "loadRoomMessages : " + filenames.length + " rooms in " + (System.currentTimeMillis() - start) + " ms");

            // extract the tokens list
            filenames = mStoreRoomsTokensFolderFile.list();

            start = System.currentTimeMillis();

            for(int index = 0; succeed && (index < filenames.length); index++) {
                succeed &= loadRoomToken(filenames[index]);
            }

            Log.d(LOG_TAG, "loadRoomToken : " + filenames.length + " rooms in " + (System.currentTimeMillis() - start) + " ms");

        } catch (Exception e) {
            succeed = false;
            Log.e(LOG_TAG, "loadRoomToken failed : " + e.getMessage());
        }

        return succeed;
    }

    /**
     * Delete the room state file.
     * @param roomId the room id.
     */
    private void deleteRoomStateFile(String roomId) {
        // states list
        File statesFile = new File(mOldStoreRoomsStateFolderFile, roomId);

        if (statesFile.exists()) {
            try {
                statesFile.delete();
            } catch (Exception e) {
            }
        }

        statesFile = new File(mGzStoreRoomsStateFolderFile, roomId);

        if (statesFile.exists()) {
            try {
                statesFile.delete();
            } catch (Exception e) {
            }
        }

    }

    /**
     * Save the room state.
     * @param roomId the room id.
     */
    private void saveRoomState(String roomId) {
        try {
            deleteRoomStateFile(roomId);

            File roomStateFile = new File(mGzStoreRoomsStateFolderFile, roomId);
            Room room = mRooms.get(roomId);

            if (null != room) {
                long start1 = System.currentTimeMillis();
                FileOutputStream fos = new FileOutputStream(roomStateFile);
                GZIPOutputStream gz = new GZIPOutputStream(fos);
                ObjectOutputStream out = new ObjectOutputStream(gz);

                out.writeObject(room.getLiveState());
                out.close();
                Log.d(LOG_TAG, "saveRoomsState " + room.getLiveState().getMembers().size() + " : " + (System.currentTimeMillis() - start1) + " ms");
            }

        } catch (Exception e) {
            Log.e(LOG_TAG, "saveRoomsState failed : " + e.getMessage());
        }
    }

    /**
     * Flush the room state files.
     */
    private void saveRoomStates() {
        if ((mRoomsToCommitForStates.size() > 0) && (null != mFileStoreHandler)) {
            // get the list
            final ArrayList<String> fRoomsToCommitForStates = mRoomsToCommitForStates;
            mRoomsToCommitForStates = new ArrayList<String>();

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    mFileStoreHandler.post(new Runnable() {
                        public void run() {
                            if (!isKilled()) {
                                long start = System.currentTimeMillis();

                                for (String roomId : fRoomsToCommitForStates) {
                                    saveRoomState(roomId);
                                }

                                Log.d(LOG_TAG, "saveRoomsState : " + fRoomsToCommitForStates.size() + " rooms in " + (System.currentTimeMillis() - start) + " ms");
                            }
                        }
                    });
                }
            };

            Thread t = new Thread(r);
            t.start();
        }
    }

    /**
     * Load a room state from the file system.
     * @param roomId the room id.
     * @return true if the operation succeeds.
     */
    private boolean loadRoomState(final String roomId) {
        Boolean succeed = true;

        Room room = getRoom(roomId);

        // should always be true
        if (null != room) {
            RoomState liveState = null;
            boolean shouldSave = false;

            try {
                // the room state is not zipped
                File messagesListFile = new File(mGzStoreRoomsStateFolderFile, roomId);

                // new format
                if (messagesListFile.exists()) {
                    FileInputStream fis = new FileInputStream(messagesListFile);
                    GZIPInputStream gz = new GZIPInputStream(fis);
                    ObjectInputStream ois = new ObjectInputStream(gz);
                    liveState = (RoomState) ois.readObject();
                    ois.close();

                    // delete old file
                    messagesListFile = new File(mOldStoreRoomsStateFolderFile, roomId);
                    if (messagesListFile.exists()) {
                        messagesListFile.delete();
                    }

                } else {
                    messagesListFile = new File(mOldStoreRoomsStateFolderFile, roomId);
                    FileInputStream fis = new FileInputStream(messagesListFile);
                    ObjectInputStream ois = new ObjectInputStream(fis);
                    liveState = (RoomState) ois.readObject();
                    ois.close();

                    shouldSave = true;
                }
            } catch (Exception e) {
                succeed = false;
                Log.e(LOG_TAG, "loadRoomState failed : " + e.getMessage());
            }

            if (null != liveState) {
                room.setLiveState(liveState);

                // force to use the new format
                if (shouldSave) {
                    saveRoomState(roomId);
                }
            } else {
                deleteRoom(roomId);
            }
        } else {
            try {
                File messagesListFile = new File(mOldStoreRoomsStateFolderFile, roomId);
                messagesListFile.delete();

                messagesListFile = new File(mGzStoreRoomsStateFolderFile, roomId);
                messagesListFile.delete();

            } catch (Exception e) {
                Log.e(LOG_TAG, "loadRoomState failed to delete a file : " + e.getMessage());
            }
        }

        return succeed;
    }

    /**
     * Load room state from the file system.
     * @return true if the operation succeeds.
     */
    private boolean loadRoomsState() {
        Boolean succeed = true;

        try {
            long start = System.currentTimeMillis();

            String[] filenames = null;

            filenames = mGzStoreRoomsStateFolderFile.list();

            for(int index = 0; succeed && (index < filenames.length); index++) {
                succeed &= loadRoomState(filenames[index]);
            }

            // convert old format to the new one.
            if (mOldStoreRoomsStateFolderFile.exists()) {
                filenames = mOldStoreRoomsStateFolderFile.list();

                for(int index = 0; succeed && (index < filenames.length); index++) {
                    succeed &= loadRoomState(filenames[index]);
                }
            }

            Log.d(LOG_TAG, "loadRoomsState " + filenames.length + " rooms in " + (System.currentTimeMillis() - start) + " ms");

        } catch (Exception e) {
            succeed = false;
            Log.e(LOG_TAG, "loadRoomsState failed : " + e.getMessage());
        }

        return succeed;
    }

    /**
     * Delete the room summary file.
     * @param roomId the room id.
     */
    private void deleteRoomSummaryFile(String roomId) {
        // states list
        File statesFile = new File(mStoreRoomsSummaryFolderFile, roomId);

        // remove the files
        if (statesFile.exists()) {
            try {
                statesFile.delete();
            } catch (Exception e) {
                Log.e(LOG_TAG, "deleteRoomSummaryFile failed : " + e.getMessage());
            }
        }
    }

    /**
     * Flush the pending summaries.
     */
    private void saveSummaries() {
        if ((mRoomsToCommitForSummaries.size() > 0) && (null != mFileStoreHandler)) {
            // get the list
            final ArrayList<String> fRoomsToCommitForSummaries = mRoomsToCommitForSummaries;
            mRoomsToCommitForSummaries = new ArrayList<String>();

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    mFileStoreHandler.post(new Runnable() {
                        public void run() {
                            if (!isKilled()) {
                                long start = System.currentTimeMillis();

                                for (String roomId : fRoomsToCommitForSummaries) {
                                    try {
                                        deleteRoomSummaryFile(roomId);

                                        File roomSummaryFile = new File(mStoreRoomsSummaryFolderFile, roomId);
                                        RoomSummary roomSummary = mRoomSummaries.get(roomId);

                                        if (null != roomSummary) {
                                            roomSummary.getLatestEvent().prepareSerialization();

                                            FileOutputStream fos = new FileOutputStream(roomSummaryFile);
                                            ObjectOutputStream out = new ObjectOutputStream(fos);

                                            out.writeObject(roomSummary);
                                            out.close();
                                        }

                                    } catch (Exception e) {
                                        Log.e(LOG_TAG, "saveSummaries failed : " + e.getMessage());
                                    }
                                }

                                Log.d(LOG_TAG, "saveSummaries : " + fRoomsToCommitForSummaries.size() + " summaries in " + (System.currentTimeMillis() - start) + " ms");
                            }
                        }
                    });
                }
            };

            Thread t = new Thread(r);
            t.start();
        }
    }

    /**
     * Load the room summary from the files system.
     * @param roomId the room id.
     * @return true if the operation succeeds;
     */
    private boolean loadSummary(final String roomId) {
        Boolean succeed = true;

        // do not check if the room exists here.
        // if the user is invited to a room, the room object is not created until it is joined.
        RoomSummary summary = null;

        try {
            File messagesListFile = new File(mStoreRoomsSummaryFolderFile, roomId);

            FileInputStream fis = new FileInputStream(messagesListFile);
            ObjectInputStream ois = new ObjectInputStream(fis);

            summary = (RoomSummary) ois.readObject();
            ois.close();
        } catch (Exception e){
            succeed = false;
            Log.e(LOG_TAG, "loadSummary failed : " + e.getMessage());
        }

        if (null != summary) {
            summary.getLatestEvent().finalizeDeserialization();
            mRoomSummaries.put(roomId, summary);
        }

        return succeed;
    }
    /**
     * Load room summaries from the file system.
     * @return true if the operation succeeds.
     */
    private Boolean loadSummaries() {
        Boolean succeed = true;
        try {
            // extract the room states
            String[] filenames = mStoreRoomsSummaryFolderFile.list();

            long start = System.currentTimeMillis();

            for(int index = 0; succeed && (index < filenames.length); index++) {
                succeed &= loadSummary(filenames[index]);
            }

            Log.d(LOG_TAG, "loadSummaries " + filenames.length + " rooms in " + (System.currentTimeMillis() - start) + " ms");
        }
        catch (Exception e) {
            succeed = false;
            Log.e(LOG_TAG, "loadSummaries failed : " + e.getMessage());
        }

        return succeed;
    }

    /**
     * Load the metadata info from the file system.
     */
    private void loadMetaData() {
        long start = System.currentTimeMillis();

        // init members
        mEventStreamToken = null;
        mMetadata = null;

        try {
            File metaDataFile = new File(mStoreFolderFile, MXFILE_STORE_METADATA_FILE_NAME);

            if (metaDataFile.exists()) {
                FileInputStream fis = new FileInputStream(metaDataFile);
                ObjectInputStream out = new ObjectInputStream(fis);

                mMetadata = (MXFileStoreMetaData)out.readObject();

                // remove pending \n
                if (null != mMetadata.mUserDisplayName) {
                    mMetadata.mUserDisplayName.trim();
                }

                // extract the latest event stream token
                mEventStreamToken = mMetadata.mEventStreamToken;
            }

        } catch (Exception e) {
            Log.e(LOG_TAG, "loadMetaData failed : " + e.getMessage());
            mMetadata = null;
            mEventStreamToken = null;
        }

        Log.d(LOG_TAG, "loadMetaData : " + (System.currentTimeMillis() - start) + " ms");
    }

    /**
     * flush the metadata info from the file system.
     */
    private void saveMetaData() {
        if ((mMetaDataHasChanged) && (null != mFileStoreHandler)) {
            mMetaDataHasChanged = false;

            final MXFileStoreMetaData fMetadata = mMetadata.deepCopy();

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    mFileStoreHandler.post(new Runnable() {
                        public void run() {
                            if (!mIsKilled) {
                                long start = System.currentTimeMillis();

                                try {
                                    File metaDataFile = new File(mStoreFolderFile, MXFILE_STORE_METADATA_FILE_NAME);

                                    if (metaDataFile.exists()) {
                                        metaDataFile.delete();
                                    }

                                    FileOutputStream fos = new FileOutputStream(metaDataFile);
                                    ObjectOutputStream out = new ObjectOutputStream(fos);

                                    out.writeObject(fMetadata);
                                    out.close();
                                } catch (Exception e) {
                                    Log.e(LOG_TAG, "saveMetaData failed : " + e.getMessage());
                                }

                                Log.d(LOG_TAG, "saveMetaData : " + (System.currentTimeMillis() - start) + " ms");
                            }
                        }
                    });
                }
            };

            Thread t = new Thread(r);
            t.start();
        }
    }
}
