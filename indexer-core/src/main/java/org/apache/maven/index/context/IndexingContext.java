/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.index.context;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.maven.index.artifact.GavCalculator;

/**
 * An indexing context is representing artifact repository for indexing and searching. Indexing context is a statefull
 * component, it keeps state of index readers and writers.
 * 
 * @author Jason van Zyl
 * @author Tamas Cservenak
 * @author Eugene Kuleshov
 */
public interface IndexingContext
{
    /**
     * Standard name of the full repository index that is used when clients requesting index information have nothing to
     * start with.
     */
    public static final String INDEX_FILE = "nexus-maven-repository-index";

    /**
     * A prefix used for all index property names
     */
    public static final String INDEX_PROPERTY_PREFIX = "nexus.index.";

    /**
     * A property name used to specify index id
     */
    public static final String INDEX_ID = INDEX_PROPERTY_PREFIX + "id";

    /**
     * A property name used to specify legacy index timestam (the last update time)
     */
    public static final String INDEX_LEGACY_TIMESTAMP = INDEX_PROPERTY_PREFIX + "time";

    /**
     * A property name used to specify index timtestamp
     */
    public static final String INDEX_TIMESTAMP = INDEX_PROPERTY_PREFIX + "timestamp";

    /**
     * A prefix used to specify an incremental update chunk name
     */
    public static final String INDEX_CHUNK_PREFIX = INDEX_PROPERTY_PREFIX + "incremental-";

    /**
     * A date format used for index timestamp
     */
    public static final String INDEX_TIME_FORMAT = "yyyyMMddHHmmss.SSS Z";

    /**
     * A date format used for incremental update chunk names
     */
    public static final String INDEX_TIME_DAY_FORMAT = "yyyyMMdd";

    /**
     * A counter used to id the chunks
     */
    public static final String INDEX_CHUNK_COUNTER = INDEX_PROPERTY_PREFIX + "last-incremental";

    /**
     * An id that defines the current incremental chain. If when checking remote repo, the index chain doesnt match
     * you'll know that you need to download the full index
     */
    public static final String INDEX_CHAIN_ID = INDEX_PROPERTY_PREFIX + "chain-id";

    /**
     * Returns this indexing context id.
     */
    String getId();

    /**
     * Returns repository id.
     */
    String getRepositoryId();

    /**
     * Returns location for the local repository.
     */
    File getRepository();

    /**
     * Returns public repository url.
     */
    String getRepositoryUrl();

    /**
     * Returns url for the index update
     */
    String getIndexUpdateUrl();

    /**
     * Is the context searchable when doing "non-targeted" searches? Ie. Should it take a part when searching without
     * specifying context?
     * 
     * @return
     */
    boolean isSearchable();

    /**
     * Sets is the context searchable when doing "non-targeted" searches.
     * 
     * @param searchable
     */
    void setSearchable( boolean searchable );

    /**
     * Returns index update time
     */
    Date getTimestamp();

    void updateTimestamp()
        throws IOException;

    void updateTimestamp( boolean save )
        throws IOException;

    void updateTimestamp( boolean save, Date date )
        throws IOException;

    /**
     * Returns a number that represents the "size" useful for doing comparisons between contexts (which one has more
     * data indexed?). The number return does not represent the count of ArtifactInfos, neither other "meaningful" info,
     * it is purely to be used for inter-context comparisons only!
     * 
     * @return
     * @throws IOException
     */
    int getSize()
        throws IOException;

    /**
     * Returns the Lucene IndexReader of this context.
     * 
     * @return reader
     * @throws IOException
     */
    IndexReader getIndexReader()
        throws IOException;

    /**
     * Returns the Lucene IndexSearcher of this context.
     * 
     * @return searcher
     * @throws IOException
     */
    IndexSearcher getIndexSearcher()
        throws IOException;

    /**
     * Returns the Lucene IndexWriter of this context.
     * 
     * @return indexWriter
     * @throws IOException
     */
    IndexWriter getIndexWriter()
        throws IOException;

    /**
     * List of IndexCreators used in this context.
     * 
     * @return list of index creators.
     */
    List<IndexCreator> getIndexCreators();

    /**
     * Returns the Lucene Analyzer of this context used for by IndexWriter and IndexSearcher. Note: this method always
     * creates a new instance of analyzer!
     * 
     * @return
     */
    Analyzer getAnalyzer();

    /**
     * Commits changes to context, eventually refreshing readers/searchers too.
     * 
     * @throws IOException
     */
    void commit()
        throws IOException;

    /**
     * Rolls back changes to context, eventually refreshing readers/searchers too.
     * 
     * @throws IOException
     */
    void rollback()
        throws IOException;

    /**
     * Optimizes index
     */
    void optimize()
        throws IOException;

    /**
     * Performs a shared locking on this context, guaranteeing that no IndexReader/Searcher/Writer close will occur. But
     * the cost of it is potentially blocking other threads, so stay in critical region locking this context as less as
     * possible.
     */
    void lock();

    /**
     * Releases the shared lock on this context.
     */
    void unlock();

    /**
     * Shuts down this context.
     */
    void close( boolean deleteFiles )
        throws IOException;

    /**
     * Purge (cleans) the context, deletes/empties the index and restores the context to new/empty state.
     * 
     * @throws IOException
     */
    void purge()
        throws IOException;

    /**
     * Merges content of given Lucene directory with this context.
     * 
     * @param directory - the directory to merge
     */
    void merge( Directory directory )
        throws IOException;

    /**
     * Merges content of given Lucene directory with this context, but filters out the unwanted ones.
     * 
     * @param directory - the directory to merge
     */
    void merge( Directory directory, DocumentFilter filter )
        throws IOException;

    /**
     * Replaces the Lucene index with the one from supplied directory.
     * 
     * @param directory
     * @throws IOException
     */
    void replace( Directory directory )
        throws IOException;

    Directory getIndexDirectory();

    File getIndexDirectoryFile();

    /**
     * Returns the GavCalculator for this Context. Implies repository layout.
     */
    GavCalculator getGavCalculator();

    /**
     * Sets all group names stored in the current indexing context
     */
    void setAllGroups( Collection<String> groups )
        throws IOException;

    /**
     * Gets all group names stored in the current indexing context
     */
    Set<String> getAllGroups()
        throws IOException;

    /**
     * Sets root group names stored in the current indexing context
     */
    void setRootGroups( Collection<String> groups )
        throws IOException;

    /**
     * Gets root group names stored in the current indexing context
     */
    Set<String> getRootGroups()
        throws IOException;

    /**
     * Rebuilds stored group names from the index
     */
    void rebuildGroups()
        throws IOException;

}
