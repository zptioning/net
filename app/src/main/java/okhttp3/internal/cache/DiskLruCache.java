/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.cache;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.Flushable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.internal.Util;
import okhttp3.internal.io.FileSystem;
import okhttp3.internal.platform.Platform;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;

import static okhttp3.internal.platform.Platform.WARN;

/**
 * A cache that uses a bounded amount of space on a filesystem. Each cache entry has a string key
 * and a fixed number of values. Each key must match the regex <strong>[a-z0-9_-]{1,64}</strong>.
 * Values are byte sequences, accessible as streams or files. Each value must be between {@code 0}
 * and {@code Integer.MAX_VALUE} bytes in length.
 *
 * <p>The cache stores its data in a directory on the filesystem. This directory must be exclusive
 * to the cache; the cache may delete or overwrite files from its directory. It is an error for
 * multiple processes to use the same cache directory at the same time.
 *
 * <p>This cache limits the number of bytes that it will store on the filesystem. When the number of
 * stored bytes exceeds the limit, the cache will remove entries in the background until the limit
 * is satisfied. The limit is not strict: the cache may temporarily exceed it while waiting for
 * files to be deleted. The limit does not include filesystem overhead or the cache journal so
 * space-sensitive applications should set a conservative limit.
 *
 * <p>Clients call {@link #edit} to create or update the values of an entry. An entry may have only
 * one editor at one time; if a value is not available to be edited then {@link #edit} will return
 * null.
 *
 * <ul>
 * <li>When an entry is being <strong>created</strong> it is necessary to supply a full set of
 * values; the empty value should be used as a placeholder if necessary.
 * <li>When an entry is being <strong>edited</strong>, it is not necessary to supply data for
 * every value; values default to their previous value.
 * </ul>
 *
 * <p>Every {@link #edit} call must be matched by a call to {@link Editor#commit} or {@link
 * Editor#abort}. Committing is atomic: a read observes the full set of values as they were before
 * or after the commit, but never a mix of values.
 *
 * <p>Clients call {@link #get} to read a snapshot of an entry. The read will observe the value at
 * the time that {@link #get} was called. Updates and removals after the call do not impact ongoing
 * reads.
 *
 * <p>This class is tolerant of some I/O errors. If files are missing from the filesystem, the
 * corresponding entries will be dropped from the cache. If an error occurs while writing a cache
 * value, the edit will fail silently. Callers should handle other problems by catching {@code
 * IOException} and responding appropriately.
 */
public final class DiskLruCache implements Closeable, Flushable {
    static final String JOURNAL_FILE = "journal";
    static final String JOURNAL_FILE_TEMP = "journal.tmp";
    static final String JOURNAL_FILE_BACKUP = "journal.bkp";
    static final String MAGIC = "libcore.io.DiskLruCache";
    static final String VERSION_1 = "1";
    static final long ANY_SEQUENCE_NUMBER = -1;
    static final Pattern LEGAL_KEY_PATTERN = Pattern.compile("[a-z0-9_-]{1,120}");
    private static final String CLEAN = "CLEAN";
    private static final String DIRTY = "DIRTY";
    private static final String REMOVE = "REMOVE";
    private static final String READ = "READ";

    /*
     * This cache uses a journal file named "journal". A typical journal file
     * looks like this:
     *     libcore.io.DiskLruCache
     *     1
     *     100
     *     2
     *
     *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
     *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
     *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
     *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
     *     DIRTY 1ab96a171faeeee38496d8b330771a7a
     *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
     *     READ 335c4c6028171cfddfbaae1a9c313c52
     *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
     *
     * The first five lines of the journal form its header. They are the
     * constant string "libcore.io.DiskLruCache", the disk cache's version,
     * the application's version, the value count, and a blank line.
     *
     * Each of the subsequent lines in the file is a record of the state of a
     * cache entry. Each line contains space-separated values: a state, a key,
     * and optional state-specific values.
     *   o DIRTY lines track that an entry is actively being created or updated.
     *     Every successful DIRTY action should be followed by a CLEAN or REMOVE
     *     action. DIRTY lines without a matching CLEAN or REMOVE indicate that
     *     temporary files may need to be deleted.
     *   o CLEAN lines track a cache entry that has been successfully published
     *     and may be read. A publish line is followed by the lengths of each of
     *     its values.
     *   o READ lines track accesses for LRU.
     *   o REMOVE lines track entries that have been deleted.
     *
     * The journal file is appended to as cache operations occur. The journal may
     * occasionally be compacted by dropping redundant lines. A temporary file named
     * "journal.tmp" will be used during compaction; that file should be deleted if
     * it exists when the cache is opened.
     *
     * 1、通过LinkedHashMap实现LRU替换
2、通过本地维护Cache操作日志保证Cache原子性与可用性，同时为防止日志过分膨胀定时执行日志精简。
3、 每一个Cache项对应两个状态副本：DIRTY，CLEAN。CLEAN表示当前可用的Cache。外部访问到cache快照均为CLEAN状态；DIRTY为编辑状态的cache。由于更新和创新都只操作DIRTY状态的副本，实现了读和写的分离。
4、每一个url请求cache有四个文件，两个状态(DIRY，CLEAN)，每个状态对应两个文件：一个0文件对应存储meta数据，一个文件存储body数据。
     */

    final FileSystem fileSystem;
    final File directory;
    private final File journalFile;
    private final File journalFileTmp;
    private final File journalFileBackup;
    private final int appVersion;
    private long maxSize;
    final int valueCount;
    private long size = 0;
    BufferedSink journalWriter;
    final LinkedHashMap<String, Entry> lruEntries
            = new LinkedHashMap<>(0, 0.75f, true);
    int redundantOpCount;
    boolean hasJournalErrors;

    // Must be read and written when synchronized on 'this'.
    boolean initialized;
    boolean closed;
    boolean mostRecentTrimFailed;
    boolean mostRecentRebuildFailed;

    /**
     * To differentiate between old and current snapshots, each entry is given a sequence number each
     * time an edit is committed. A snapshot is stale if its sequence number is not equal to its
     * entry's sequence number.
     */
    private long nextSequenceNumber = 0;

    /**
     * Used to run 'cleanupRunnable' for journal rebuilds.
     */
    private final Executor executor;
    private final Runnable cleanupRunnable = new Runnable() {
        public void run() {
            synchronized (DiskLruCache.this) {
                //如果没有初始化或者已经关闭了，则不需要清理
                if (!initialized | closed) {
                    return; // Nothing to do
                }

                try {
                    trimToSize();
                } catch (IOException ignored) {
                    //如果抛异常了，设置最近的一次清理失败
                    mostRecentTrimFailed = true;
                }

                try {
                    //如果需要清理了
                    if (journalRebuildRequired()) {
                        //重新创建journal文件
                        rebuildJournal();
                        //计数器归于0
                        redundantOpCount = 0;
                    }
                } catch (IOException e) {
                    //如果抛异常了，设置最近的一次构建失败
                    mostRecentRebuildFailed = true;
                    journalWriter = Okio.buffer(Okio.blackhole());
                }
            }
        }
    };

    DiskLruCache(FileSystem fileSystem, File directory, int appVersion, int valueCount, long maxSize,
                 Executor executor) {
        this.fileSystem = fileSystem;
        this.directory = directory;
        this.appVersion = appVersion;
        this.journalFile = new File(directory, JOURNAL_FILE);
        this.journalFileTmp = new File(directory, JOURNAL_FILE_TEMP);
        this.journalFileBackup = new File(directory, JOURNAL_FILE_BACKUP);
        this.valueCount = valueCount;
        this.maxSize = maxSize;
        this.executor = executor;
    }

    public synchronized void initialize() throws IOException {

        //断言，当持有自己锁的时候。继续执行，没有持有锁，直接抛异常
        assert Thread.holdsLock(this);
        //如果已经初始化过，则不需要再初始化，直接rerturn
        if (initialized) {
            return; // Already initialized.
        }

        // If a bkp file exists, use it instead.
        //如果有journalFileBackup文件
        if (fileSystem.exists(journalFileBackup)) {
            // If journal file also exists just delete backup file.
            //如果有journalFile文件
            if (fileSystem.exists(journalFile)) {
                //有journalFile文件 则删除journalFileBackup文件
                fileSystem.delete(journalFileBackup);
            } else {
                //没有journalFile，则将journalFileBackUp更名为journalFile
                fileSystem.rename(journalFileBackup, journalFile);
            }
        }

        // Prefer to pick up where we left off.
        if (fileSystem.exists(journalFile)) {
            //如果有journalFile文件，则对该文件，则分别调用readJournal()方法和processJournal()方法
            try {
                readJournal();
                processJournal();
                //设置初始化过标志
                initialized = true;
                return;
            } catch (IOException journalIsCorrupt) {
                Platform.get().log(WARN, "DiskLruCache " + directory + " is corrupt: "
                        + journalIsCorrupt.getMessage() + ", removing", journalIsCorrupt);
            }

            // The cache is corrupted, attempt to delete the contents of the directory. This can throw and
            // we'll let that propagate out as it likely means there is a severe filesystem problem.
            try {
                //如果没有journalFile则删除
                delete();
            } finally {
                closed = false;
            }
        }
        //重新建立journal文件
        rebuildJournal();
        initialized = true;
    }

    /**
     * Create a cache which will reside in {@code directory}. This cache is lazily initialized on
     * first access and will be created if it does not exist.
     *
     * @param directory  a writable directory
     * @param valueCount the number of values per cache entry. Must be positive.
     * @param maxSize    the maximum number of bytes this cache should use to store
     */
    public static DiskLruCache create(FileSystem fileSystem, File directory, int appVersion,
                                      int valueCount, long maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        if (valueCount <= 0) {
            throw new IllegalArgumentException("valueCount <= 0");
        }
        //这个executor其实就是DiskLruCache里面的executor
        // Use a single background thread to evict entries.
        Executor executor = new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), Util.threadFactory("OkHttp DiskLruCache", true));

        return new DiskLruCache(fileSystem, directory, appVersion, valueCount, maxSize, executor);
    }

    private void readJournal() throws IOException {
        //获取journalFile的source即输入流
        BufferedSource source = Okio.buffer(fileSystem.source(journalFile));
        try {
            //读取相关数据
            String magic = source.readUtf8LineStrict();
            String version = source.readUtf8LineStrict();
            String appVersionString = source.readUtf8LineStrict();
            String valueCountString = source.readUtf8LineStrict();
            String blank = source.readUtf8LineStrict();
            //做校验
            if (!MAGIC.equals(magic)
                    || !VERSION_1.equals(version)
                    || !Integer.toString(appVersion).equals(appVersionString)
                    || !Integer.toString(valueCount).equals(valueCountString)
                    || !"".equals(blank)) {
                throw new IOException("unexpected journal header: [" + magic + ", " + version + ", "
                        + valueCountString + ", " + blank + "]");
            }

            int lineCount = 0;
            //校验通过，开始逐行读取数据
            while (true) {
                try {
                    readJournalLine(source.readUtf8LineStrict());
                    lineCount++;
                } catch (EOFException endOfJournal) {
                    break;
                }
            }
            //读取出来的行数减去lruEntriest的集合的差值，即日志多出的"冗余"记录
            redundantOpCount = lineCount - lruEntries.size();
            // If we ended on a truncated line, rebuild the journal before appending to it.
            //source.exhausted()表示是否还多余字节，如果没有多余字节，返回true，有多月字节返回false
            if (!source.exhausted()) {
                //如果有多余字节，则重新构建下journal文件，主要是写入头文件，以便下次读的时候，根据头文件进行校验
                rebuildJournal();
            } else {
                //获取这个文件的Sink
                journalWriter = newJournalWriter();
            }
        } finally {
            Util.closeQuietly(source);
        }
    }

    private BufferedSink newJournalWriter() throws FileNotFoundException {
        Sink fileSink = fileSystem.appendingSink(journalFile);
        Sink faultHidingSink = new FaultHidingSink(fileSink) {
            @Override
            protected void onException(IOException e) {
                assert (Thread.holdsLock(DiskLruCache.this));
                hasJournalErrors = true;
            }
        };
        return Okio.buffer(faultHidingSink);
    }

    private void readJournalLine(String line) throws IOException {
        //获取空串的position，表示头
        int firstSpace = line.indexOf(' ');
        //空串的校验
        if (firstSpace == -1) {
            throw new IOException("unexpected journal line: " + line);
        }
        //第一个字符的位置
        int keyBegin = firstSpace + 1;
        // 方法返回第一个空字符在此字符串中第一次出现，在指定的索引即keyBegin开始搜索，
        // 所以secondSpace是爱这个字符串中的空字符(不包括这一行最左侧的那个空字符)
        int secondSpace = line.indexOf(' ', keyBegin);
        final String key;
        //如果没有中间的空字符
        if (secondSpace == -1) {
            //截取剩下的全部字符串构成key
            key = line.substring(keyBegin);
            if (firstSpace == REMOVE.length() && line.startsWith(REMOVE)) {
                //如果解析的是REMOVE信息，则在lruEntries里面删除这个key
                lruEntries.remove(key);
                return;
            }
        } else {
            //如果含有中间间隔的空字符，则截取这个中间间隔到左侧空字符之间的字符串，构成key
            key = line.substring(keyBegin, secondSpace);
        }
        //获取key后，根据key取出Entry对象
        Entry entry = lruEntries.get(key);
        //如果Entry为null，则表明内存中没有，则new一个，并把它放到内存中。
        if (entry == null) {
            entry = new Entry(key);
            lruEntries.put(key, entry);
        }
        //如果是CLEAN开头
        if (secondSpace != -1 && firstSpace == CLEAN.length() && line.startsWith(CLEAN)) {
            //line.substring(secondSpace + 1) 为获取中间空格后面的内容，然后按照空字符分割，
            // 设置entry的属性，表明是干净的数据，不能编辑。
            String[] parts = line.substring(secondSpace + 1).split(" ");
            entry.readable = true;
            entry.currentEditor = null;
            entry.setLengths(parts);
        } else if (secondSpace == -1 && firstSpace == DIRTY.length() && line.startsWith(DIRTY)) {
            //如果是以DIRTY开头，则设置一个新的Editor，表明可编辑
            entry.currentEditor = new Editor(entry);
        } else if (secondSpace == -1 && firstSpace == READ.length() && line.startsWith(READ)) {
            // This work was already done by calling lruEntries.get().
        } else {
            throw new IOException("unexpected journal line: " + line);
        }
    }
    /*
    * 1、如果是CLEAN的话，对这个entry的文件长度进行更新
2、如果是DIRTY，说明这个值正在被操作，还没有commit，于是给entry分配一个Editor。
3、如果是READ，说明这个值被读过了，什么也不做。

看下journal文件你就知道了
 1 *     libcore.io.DiskLruCache
 2 *     1
 3 *     100
 4 *     2
 5 *
 6 *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
 7 *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
 8 *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
 9 *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
10 *     DIRTY 1ab96a171faeeee38496d8b330771a7a
11 *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
12 *     READ 335c4c6028171cfddfbaae1a9c313c52
13 *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6

    * */

    /**
     * Computes the initial size and collects garbage as a part of opening the cache. Dirty entries
     * are assumed to be inconsistent and will be deleted.
     *
     * 先是删除了journalFileTmp文件
     * 然后调用for循环获取链表中的所有Entry，如果Entry的中Editor!=null，
     * 则表明Entry数据时脏的DIRTY，所以不能读，进而删除Entry下的缓存文件，
     * 并且将Entry从lruEntries中移除。如果Entry的Editor==null，则证明该Entry下的缓存文件可用，
     * 记录它所有缓存文件的缓存数量，结果赋值给size。
     * readJournal()方法里面调用了rebuildJournal()，initialize()方法同样会readJourna，但
     * 是这里说明下：readJournal里面调用的rebuildJournal()是有条件限制的，initialize()是一定会调用的。
     * 那我们来研究下readJournal()
     *

     */
    private void processJournal() throws IOException {
        fileSystem.delete(journalFileTmp);
        for (Iterator<Entry> i = lruEntries.values().iterator(); i.hasNext(); ) {
            Entry entry = i.next();
            if (entry.currentEditor == null) {
                for (int t = 0; t < valueCount; t++) {
                    size += entry.lengths[t];
                }
            } else {
                entry.currentEditor = null;
                for (int t = 0; t < valueCount; t++) {
                    fileSystem.delete(entry.cleanFiles[t]);
                    fileSystem.delete(entry.dirtyFiles[t]);
                }
                i.remove();
            }
        }
    }

    /**
     * Creates a new journal that omits redundant information. This replaces the current journal if it
     * exists.
     * 获取一个写入流，将lruEntries集合中的Entry对象写入tmp文件中，
     * 根据Entry的currentEditor的值判断是CLEAN还是DIRTY,写入该Entry的key，
     * 如果是CLEAN还要写入文件的大小bytes。然后就是把journalFileTmp更名为journalFile，
     * 然后将journalWriter跟文件绑定，通过它来向journalWrite写入数据，最后设置一些属性。
     * 我们可以砍到，rebuild操作是以lruEntries为准，把DIRTY和CLEAN的操作都写回到journal中。
     * 但发现没有，其实没有改动真正的value，只不过重写了一些事务的记录。
     * 事实上，lruEntries和journal文件共同确定了cache数据的有效性。lruEntries是索引，journal是归档。至此序列化部分就已经结束了

     */
    synchronized void rebuildJournal() throws IOException {
        //如果写入流不为空
        if (journalWriter != null) {
            //关闭写入流
            journalWriter.close();
        }
        //通过okio获取一个写入BufferedSinke
        BufferedSink writer = Okio.buffer(fileSystem.sink(journalFileTmp));
        try {
            //写入相关信息和读取向对应，这时候大家想下readJournal
            writer.writeUtf8(MAGIC).writeByte('\n');
            writer.writeUtf8(VERSION_1).writeByte('\n');
            writer.writeDecimalLong(appVersion).writeByte('\n');
            writer.writeDecimalLong(valueCount).writeByte('\n');
            writer.writeByte('\n');

            //遍历lruEntries里面的值
            for (Entry entry : lruEntries.values()) {
                //如果editor不为null，则为DIRTY数据
                if (entry.currentEditor != null) {
                    //在开头写上 DIRTY，然后写上 空字符
                    writer.writeUtf8(DIRTY).writeByte(' ');
                    //把entry的key写上
                    writer.writeUtf8(entry.key);
                    //换行
                    writer.writeByte('\n');
                } else {
                    //如果editor为null，则为CLEAN数据,  在开头写上 CLEAN，然后写上 空字符
                    writer.writeUtf8(CLEAN).writeByte(' ');
                    //把entry的key写上
                    writer.writeUtf8(entry.key);
                    //结尾接上两个十进制的数字，表示长度
                    entry.writeLengths(writer);
                    //换行
                    writer.writeByte('\n');
                }
            }
        } finally {
            //最后关闭写入流
            writer.close();
        }
        //如果存在journalFile
        if (fileSystem.exists(journalFile)) {
            //把journalFile文件重命名为journalFileBackup
            fileSystem.rename(journalFile, journalFileBackup);
        }
        //然后又把临时文件，重命名为journalFile
        fileSystem.rename(journalFileTmp, journalFile);
        //删除备份文件
        fileSystem.delete(journalFileBackup);
        //拼接一个新的写入流
        journalWriter = newJournalWriter();
        //设置没有error标志
        hasJournalErrors = false;
        //设置最近重新创建journal文件成功
        mostRecentRebuildFailed = false;
    }
    /**
     * Returns a snapshot of the entry named {@code key}, or null if it doesn't exist is not currently
     * readable. If a value is returned, it is moved to the head of the LRU queue.
     */
    public synchronized Snapshot get(String key) throws IOException {
        //初始化
        initialize();
        //检查缓存是否已经关闭
        checkNotClosed();
        //检验key
        validateKey(key);
        //如果以上都通过，先获取内存中的数据，即根据key在linkedList查找
        Entry entry = lruEntries.get(key);
        //如果没有值，或者有值，但是值不可读
        if (entry == null || !entry.readable) return null;
        //获取entry里面的snapshot的值
        Snapshot snapshot = entry.snapshot();
        //如果有snapshot为null，则直接返回null
        if (snapshot == null) return null;
        //如果snapshot不为null
        //计数器自加1
        redundantOpCount++;
        //把这个内容写入文档中
        journalWriter.writeUtf8(READ).writeByte(' ').writeUtf8(key).writeByte('\n');
        //如果超过上限
        if (journalRebuildRequired()) {
            //开始清理
            executor.execute(cleanupRunnable);
        }
        //返回数据
        return snapshot;
    }

    /**
     * Returns an editor for the entry named {@code key}, or null if another edit is in progress.
     */
    public Editor edit(String key) throws IOException {
        return edit(key, ANY_SEQUENCE_NUMBER);
    }

    /*
    * (1)如果已经有个别的editor在操作这个entry了，那就返回null
(2)无时无刻不在进行cleanup判断进行cleanup操作
(3)会把当前的key在journal文件标记为dirty状态，表示这条记录正在被编辑
(4)如果没有entry，会new一个出来
*/
    synchronized Editor edit(String key, long expectedSequenceNumber) throws IOException {
        //初始化
        initialize();
        //流关闭检测
        checkNotClosed();
        //检测key
        validateKey(key);
        //根据key找到Entry
        Entry entry = lruEntries.get(key);
        //如果快照是旧的
        if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER && (entry == null
                || entry.sequenceNumber != expectedSequenceNumber)) {
            return null; // Snapshot is stale.
        }
        //如果 entry.currentEditor != null 表明正在编辑，是DIRTY
        if (entry != null && entry.currentEditor != null) {
            return null; // Another edit is in progress.
        }
        //如果最近清理失败，或者最近重新构建失败，我们需要开始清理任务
        //我大概翻译下注释：操作系统已经成为我们的敌人，如果清理任务失败，它意味着我们存储了过多的数据，
        // 因此我们允许超过这个限制，所以不建议编辑。如果构建日志失败，writer这个写入流就会无效，
        // 所以文件无法及时更新，导致我们无法继续编辑，会引起文件泄露。如果满足以上两种情况，
        // 我们必须进行清理，摆脱这种不好的状态。
        if (mostRecentTrimFailed || mostRecentRebuildFailed) {
            // The OS has become our enemy! If the trim job failed, it means we are storing more data than
            // requested by the user. Do not allow edits so we do not go over that limit any further. If
            // the journal rebuild failed, the journal writer will not be active, meaning we will not be
            // able to record the edit, causing file leaks. In both cases, we want to retry the clean up
            // so we can get out of this state!
            //开启清理任务
            executor.execute(cleanupRunnable);
            return null;
        }

        // Flush the journal before creating files to prevent file leaks.
        //写入DIRTY
        journalWriter.writeUtf8(DIRTY).writeByte(' ').writeUtf8(key).writeByte('\n');
        journalWriter.flush();
        //如果journal有错误，表示不能编辑，返回null
        if (hasJournalErrors) {
            return null; // Don't edit; the journal can't be written.
        }
        //如果entry==null，则new一个，并放入lruEntries
        if (entry == null) {
            entry = new Entry(key);
            lruEntries.put(key, entry);
        }
        //根据entry 构造一个Editor
        Editor editor = new Editor(entry);
        entry.currentEditor = editor;
        return editor;
    }

    /**
     * Returns the directory where this cache stores its data.
     */
    public File getDirectory() {
        return directory;
    }

    /**
     * Returns the maximum number of bytes that this cache should use to store its data.
     */
    public synchronized long getMaxSize() {
        return maxSize;
    }

    /**
     * Changes the maximum number of bytes the cache can store and queues a job to trim the existing
     * store, if necessary.
     */
    public synchronized void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
        if (initialized) {
            executor.execute(cleanupRunnable);
        }
    }

    /**
     * Returns the number of bytes currently being used to store the values in this cache. This may be
     * greater than the max size if a background deletion is pending.
     */
    public synchronized long size() throws IOException {
        initialize();
        return size;
    }

    synchronized void completeEdit(Editor editor, boolean success) throws IOException {
        Entry entry = editor.entry;
        //如果entry的编辑器不是editor则抛异常
        if (entry.currentEditor != editor) {
            throw new IllegalStateException();
        }

        // If this edit is creating the entry for the first time, every index must have a value.
        //如果successs是true,且entry不可读表明 是第一次写回，必须保证每个index里面要有数据，这是为了保证完整性
        if (success && !entry.readable) {
            for (int i = 0; i < valueCount; i++) {
                if (!editor.written[i]) {
                    editor.abort();
                    throw new IllegalStateException("Newly created entry didn't create value for index " + i);
                }
                if (!fileSystem.exists(entry.dirtyFiles[i])) {
                    editor.abort();
                    return;
                }
            }
        }
        //遍历entry下的所有文件
        for (int i = 0; i < valueCount; i++) {
            File dirty = entry.dirtyFiles[i];
            if (success) {
                //把dirtyFile重命名为cleanFile，完成数据迁移;
                if (fileSystem.exists(dirty)) {
                    File clean = entry.cleanFiles[i];
                    fileSystem.rename(dirty, clean);
                    long oldLength = entry.lengths[i];
                    long newLength = fileSystem.size(clean);
                    entry.lengths[i] = newLength;
                    size = size - oldLength + newLength;
                }
            } else {
                //删除dirty数据
                fileSystem.delete(dirty);
            }
        }
        //计数器加1
        redundantOpCount++;
        //编辑器指向null
        entry.currentEditor = null;

        if (entry.readable | success) {
            //开始写入数据
            entry.readable = true;
            journalWriter.writeUtf8(CLEAN).writeByte(' ');
            journalWriter.writeUtf8(entry.key);
            entry.writeLengths(journalWriter);
            journalWriter.writeByte('\n');
            if (success) {
                entry.sequenceNumber = nextSequenceNumber++;
            }
        } else {
            //删除key，并且记录
            lruEntries.remove(entry.key);
            journalWriter.writeUtf8(REMOVE).writeByte(' ');
            journalWriter.writeUtf8(entry.key);
            journalWriter.writeByte('\n');
        }
        journalWriter.flush();
        //检查是否需要清理
        if (size > maxSize || journalRebuildRequired()) {
            executor.execute(cleanupRunnable);
        }
    }

    /**
     * We only rebuild the journal when it will halve the size of the journal and eliminate at least
     * 2000 ops.
     */
    boolean journalRebuildRequired() {
        final int redundantOpCompactThreshold = 2000;
        return redundantOpCount >= redundantOpCompactThreshold
                && redundantOpCount >= lruEntries.size();
    }

    /**
     * Drops the entry for {@code key} if it exists and can be removed. If the entry for {@code key}
     * is currently being edited, that edit will complete normally but its value will not be stored.
     *
     * @return true if an entry was removed.
     */
    public synchronized boolean remove(String key) throws IOException {
        initialize();

        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (entry == null) return false;
        boolean removed = removeEntry(entry);
        if (removed && size <= maxSize) mostRecentTrimFailed = false;
        return removed;
    }

    boolean removeEntry(Entry entry) throws IOException {
        if (entry.currentEditor != null) {
            //让这个editor正常的结束
            entry.currentEditor.detach(); // Prevent the edit from completing normally.
        }

        for (int i = 0; i < valueCount; i++) {
            //删除entry对应的clean文件
            fileSystem.delete(entry.cleanFiles[i]);
            //缓存大小减去entry的小小
            size -= entry.lengths[i];
            //设置entry的缓存为0
            entry.lengths[i] = 0;
        }
        //计数器自加1
        redundantOpCount++;
        //在journalWriter添加一条删除记录
        journalWriter.writeUtf8(REMOVE).writeByte(' ').writeUtf8(entry.key).writeByte('\n');
        //linkedList删除这个entry
        lruEntries.remove(entry.key);
        //如果需要重新构建
        if (journalRebuildRequired()) {
            //开启清理任务
            executor.execute(cleanupRunnable);
        }
        return true;
    }

    /**
     * Returns true if this cache has been closed.
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    private synchronized void checkNotClosed() {
        if (isClosed()) {
            throw new IllegalStateException("cache is closed");
        }
    }

    /**
     * Force buffered operations to the filesystem.
     */
    @Override
    public synchronized void flush() throws IOException {
        if (!initialized) return;

        checkNotClosed();
        trimToSize();
        journalWriter.flush();
    }

    /**
     * Closes this cache. Stored values will remain on the filesystem.
     */
    @Override
    public synchronized void close() throws IOException {
        if (!initialized || closed) {
            closed = true;
            return;
        }
        // Copying for safe iteration.
        for (Entry entry : lruEntries.values().toArray(new Entry[lruEntries.size()])) {
            if (entry.currentEditor != null) {
                entry.currentEditor.abort();
            }
        }
        trimToSize();
        journalWriter.close();
        journalWriter = null;
        closed = true;
    }

    void trimToSize() throws IOException {
        //如果超过上限
        while (size > maxSize) {
            //取出一个Entry
            Entry toEvict = lruEntries.values().iterator().next();
            //删除这个Entry
            removeEntry(toEvict);
        }
        mostRecentTrimFailed = false;
    }

    /**
     * Closes the cache and deletes all of its stored values. This will delete all files in the cache
     * directory including files that weren't created by the cache.
     */
    public void delete() throws IOException {
        close();
        fileSystem.deleteContents(directory);
    }

    /**
     * Deletes all stored values from the cache. In-flight edits will complete normally but their
     * values will not be stored.
     */
    public synchronized void evictAll() throws IOException {
        initialize();
        // Copying for safe iteration.
        for (Entry entry : lruEntries.values().toArray(new Entry[lruEntries.size()])) {
            removeEntry(entry);
        }
        mostRecentTrimFailed = false;
    }

    private void validateKey(String key) {
        Matcher matcher = LEGAL_KEY_PATTERN.matcher(key);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"");
        }
    }

    /**
     * Returns an iterator over the cache's current entries. This iterator doesn't throw {@code
     * ConcurrentModificationException}, but if new entries are added while iterating, those new
     * entries will not be returned by the iterator. If existing entries are removed during iteration,
     * they will be absent (unless they were already returned).
     *
     * <p>If there are I/O problems during iteration, this iterator fails silently. For example, if
     * the hosting filesystem becomes unreachable, the iterator will omit elements rather than
     * throwing exceptions.
     *
     * <p><strong>The caller must {@link Snapshot#close close}</strong> each snapshot returned by
     * {@link Iterator#next}. Failing to do so leaks open files!
     *
     * <p>The returned iterator supports {@link Iterator#remove}.
     */
    public synchronized Iterator<Snapshot> snapshots() throws IOException {
        initialize();
        return new Iterator<Snapshot>() {
            /** Iterate a copy of the entries to defend against concurrent modification errors. */
            final Iterator<Entry> delegate = new ArrayList<>(lruEntries.values()).iterator();

            /** The snapshot to return from {@link #next}. Null if we haven't computed that yet. */
            Snapshot nextSnapshot;

            /** The snapshot to remove with {@link #remove}. Null if removal is illegal. */
            Snapshot removeSnapshot;

            @Override
            public boolean hasNext() {
                if (nextSnapshot != null) return true;

                synchronized (DiskLruCache.this) {
                    // If the cache is closed, truncate the iterator.
                    if (closed) return false;

                    while (delegate.hasNext()) {
                        Entry entry = delegate.next();
                        Snapshot snapshot = entry.snapshot();
                        if (snapshot == null) continue; // Evicted since we copied the entries.
                        nextSnapshot = snapshot;
                        return true;
                    }
                }

                return false;
            }

            @Override
            public Snapshot next() {
                if (!hasNext()) throw new NoSuchElementException();
                removeSnapshot = nextSnapshot;
                nextSnapshot = null;
                return removeSnapshot;
            }

            @Override
            public void remove() {
                if (removeSnapshot == null)
                    throw new IllegalStateException("remove() before next()");
                try {
                    DiskLruCache.this.remove(removeSnapshot.key);
                } catch (IOException ignored) {
                    // Nothing useful to do here. We failed to remove from the cache. Most likely that's
                    // because we couldn't update the journal, but the cached entry will still be gone.
                } finally {
                    removeSnapshot = null;
                }
            }
        };
    }

    /** A snapshot of the values for an entry. */
    public final class Snapshot implements Closeable {
        private final String key;  //也有一个key
        private final long sequenceNumber; //序列号
        private final Source[] sources; //可以读入数据的流   这么多的流主要是从cleanFile中读取数据
        private final long[] lengths; //与上面的流一一对应

        //构造器就是对上面这些属性进行赋值
        Snapshot(String key, long sequenceNumber, Source[] sources, long[] lengths) {
            this.key = key;
            this.sequenceNumber = sequenceNumber;
            this.sources = sources;
            this.lengths = lengths;
        }

        public String key() {
            return key;
        }
        //edit方法主要就是调用DiskLruCache的edit方法了，入参是该Snapshot对象的两个属性key和sequenceNumber.
        /**
         * Returns an editor for this snapshot's entry, or null if either the entry has changed since
         * this snapshot was created or if another edit is in progress.
         */
        public Editor edit() throws IOException {
            return DiskLruCache.this.edit(key, sequenceNumber);
        }

        /** Returns the unbuffered stream with the value for {@code index}. */
        public Source getSource(int index) {
            return sources[index];
        }

        /** Returns the byte length of the value for {@code index}. */
        public long getLength(int index) {
            return lengths[index];
        }

        public void close() {
            for (Source in : sources) {
                Util.closeQuietly(in);
            }
        }
    }

    /**
     * Edits the values for an entry.
     */
    /** Edits the values for an entry. */
    public final class Editor {
        final Entry entry;
        final boolean[] written;
        private boolean done;

        Editor(Entry entry) {
            this.entry = entry;
            this.written = (entry.readable) ? null : new boolean[valueCount];
        }

        /**
         * Prevents this editor from completing normally. This is necessary either when the edit causes
         * an I/O error, or if the target entry is evicted while this editor is active. In either case
         * we delete the editor's created files and prevent new files from being created. Note that once
         * an editor has been detached it is possible for another editor to edit the entry.
         *这里说一下detach方法，当编辑器(Editor)处于io操作的error的时候，或者editor正在被调用的时候而被清
         *除的，为了防止编辑器可以正常的完成。我们需要删除编辑器创建的文件，并防止创建新的文件。如果编
         *辑器被分离，其他的编辑器可以编辑这个Entry
         */
        void detach() {
            if (entry.currentEditor == this) {
                for (int i = 0; i < valueCount; i++) {
                    try {
                        fileSystem.delete(entry.dirtyFiles[i]);
                    } catch (IOException e) {
                        // This file is potentially leaked. Not much we can do about that.
                    }
                }
                entry.currentEditor = null;
            }
        }

        /**
         * Returns an unbuffered input stream to read the last committed value, or null if no value has
         * been committed.
         * 获取cleanFile的输入流 在commit的时候把done设为true
         */
        public Source newSource(int index) {
            synchronized (DiskLruCache.this) {
                //如果已经commit了，不能读取了
                if (done) {
                    throw new IllegalStateException();
                }
                //如果entry不可读，并且已经有编辑器了(其实就是dirty)
                if (!entry.readable || entry.currentEditor != this) {
                    return null;
                }
                try {
                    //通过filesystem获取cleanFile的输入流
                    return fileSystem.source(entry.cleanFiles[index]);
                } catch (FileNotFoundException e) {
                    return null;
                }
            }
        }

        /**
         * Returns a new unbuffered output stream to write the value at {@code index}. If the underlying
         * output stream encounters errors when writing to the filesystem, this edit will be aborted
         * when {@link #commit} is called. The returned output stream does not throw IOExceptions.
         * 获取dirty文件的输出流，如果在写入数据的时候出现错误，会立即停止。返回的输出流不会抛IO异常
         */
        public Sink newSink(int index) {
            synchronized (DiskLruCache.this) {
                //已经提交，不能操作
                if (done) {
                    throw new IllegalStateException();
                }
                //如果编辑器是不自己的，不能操作
                if (entry.currentEditor != this) {
                    return Okio.blackhole();
                }
                //如果entry不可读，把对应的written设为true
                if (!entry.readable) {
                    written[index] = true;
                }
                //如果文件
                File dirtyFile = entry.dirtyFiles[index];
                Sink sink;
                try {
                    //如果fileSystem获取文件的输出流
                    sink = fileSystem.sink(dirtyFile);
                } catch (FileNotFoundException e) {
                    return Okio.blackhole();
                }
                return new FaultHidingSink(sink) {
                    @Override protected void onException(IOException e) {
                        synchronized (DiskLruCache.this) {
                            detach();
                        }
                    }
                };
            }
        }

        /**
         * Commits this edit so it is visible to readers.  This releases the edit lock so another edit
         * may be started on the same key.
         * 写好数据，一定不要忘记commit操作对数据进行提交，
         * 我们要把dirtyFiles里面的内容移动到cleanFiles里才能够让别的editor访问到
         */
        public void commit() throws IOException {
            synchronized (DiskLruCache.this) {
                if (done) {
                    throw new IllegalStateException();
                }
                if (entry.currentEditor == this) {
                    completeEdit(this, true);
                }
                done = true;
            }
        }

        /**
         * Aborts this edit. This releases the edit lock so another edit may be started on the same
         * key.
         */
        public void abort() throws IOException {
            synchronized (DiskLruCache.this) {
                if (done) {
                    throw new IllegalStateException();
                }
                if (entry.currentEditor == this) {
                    //这个方法是DiskLruCache的方法在后面讲解
                    completeEdit(this, false);
                }
                done = true;
            }
        }

        public void abortUnlessCommitted() {
            synchronized (DiskLruCache.this) {
                if (!done && entry.currentEditor == this) {
                    try {
                        completeEdit(this, false);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private final class Entry {
        final String key;
        /** 实体对应的缓存文件 */
        /** Lengths of this entry's files. */
        final long[] lengths; //文件比特数
        final File[] cleanFiles;
        final File[] dirtyFiles;
        /** 实体是否可读，可读为true，不可读为false*/
        /** True if this entry has ever been published. */
        boolean readable;

        /** 编辑器，如果实体没有被编辑过，则为null*/
        /** The ongoing edit or null if this entry is not being edited. */
        Editor currentEditor;
        /** 最近提交的Entry的序列号 */
        /** The sequence number of the most recently committed edit to this entry. */
        long sequenceNumber;

        /**
         * 通过上述代码咱们知道了，一个url对应一个Entry对象，
         * 同时，每个Entry对应两个文件，key.1存储的是Response的headers，key.2文件存储的是Response的body
         * @param key
         */
        //构造器 就一个入参 key，而key又是url，所以，一个url对应一个Entry
        Entry(String key) {

            this.key = key;
            //valueCount在构造DiskLruCache时传入的参数默认大小为2
            //具体请看Cache类的构造函数，里面通过DiskLruCache.create()方法创建了DiskLruCache，
            // 并且传入一个值为2的ENTRY_COUNT常量
            lengths = new long[valueCount];
            cleanFiles = new File[valueCount];
            dirtyFiles = new File[valueCount];

            // The names are repetitive so re-use the same builder to avoid allocations.
            StringBuilder fileBuilder = new StringBuilder(key).append('.');
            int truncateTo = fileBuilder.length();
            //由于valueCount为2,所以循环了2次，一共创建了4份文件
            //分别为key.1文件和key.1.tmp文件
            //           key.2文件和key.2.tmp文件
            for (int i = 0; i < valueCount; i++) {
                fileBuilder.append(i);
                cleanFiles[i] = new File(directory, fileBuilder.toString());
                fileBuilder.append(".tmp");
                dirtyFiles[i] = new File(directory, fileBuilder.toString());
                fileBuilder.setLength(truncateTo);
            }
        }

        /**
         * Set lengths using decimal numbers like "10123".
         */
        void setLengths(String[] strings) throws IOException {
            if (strings.length != valueCount) {
                throw invalidLengths(strings);
            }

            try {
                for (int i = 0; i < strings.length; i++) {
                    lengths[i] = Long.parseLong(strings[i]);
                }
            } catch (NumberFormatException e) {
                throw invalidLengths(strings);
            }
        }

        /**
         * Append space-prefixed lengths to {@code writer}.
         */
        void writeLengths(BufferedSink writer) throws IOException {
            for (long length : lengths) {
                writer.writeByte(' ').writeDecimalLong(length);
            }
        }

        private IOException invalidLengths(String[] strings) throws IOException {
            throw new IOException("unexpected journal line: " + Arrays.toString(strings));
        }

        /**
         * Returns a snapshot of this entry. This opens all streams eagerly to guarantee that we see a
         * single published snapshot. If we opened streams lazily then the streams could come from
         * different edits.
         */
        Snapshot snapshot() {
            //首先判断 线程是否有DiskLruCache对象的锁
            if (!Thread.holdsLock(DiskLruCache.this))
                throw new AssertionError();
            //new了一个Souce类型数组，容量为2
            Source[] sources = new Source[valueCount];
            //clone一个long类型的数组，容量为2
            long[] lengths = this.lengths.clone(); // Defensive copy since these can be zeroed out.
            //获取cleanFile的Source，用于读取cleanFile中的数据，
            // 并用得到的souce、Entry.key、Entry.length、sequenceNumber数据构造一个Snapshot对象
            try {
                for (int i = 0; i < valueCount; i++) {
                    sources[i] = fileSystem.source(cleanFiles[i]);
                }
                return new Snapshot(key, sequenceNumber, sources, lengths);
            } catch (FileNotFoundException e) {
                // A file must have been deleted manually!
                for (int i = 0; i < valueCount; i++) {
                    if (sources[i] != null) {
                        Util.closeQuietly(sources[i]);
                    } else {
                        break;
                    }
                }
                // Since the entry is no longer valid, remove it so the metadata is accurate (i.e. the cache
                // size.)
                try {
                    removeEntry(this);
                } catch (IOException ignored) {
                }
                return null;
            }
        }
    }
}
