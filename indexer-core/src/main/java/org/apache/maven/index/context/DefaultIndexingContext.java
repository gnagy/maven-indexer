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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Hits;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.artifact.GavCalculator;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.codehaus.plexus.util.StringUtils;

/**
 * The default {@link IndexingContext} implementation.
 * 
 * @author Jason van Zyl
 * @author Tamas Cservenak
 */
public class DefaultIndexingContext
    implements IndexingContext
{
    /**
     * A standard location for indices served up by a webserver.
     */
    private static final String INDEX_DIRECTORY = ".index";

    private static final String FLD_DESCRIPTOR = "DESCRIPTOR";

    private static final String FLD_DESCRIPTOR_CONTENTS = "NexusIndex";

    private static final String FLD_IDXINFO = "IDXINFO";

    private static final String VERSION = "1.0";

    private static final Term DESCRIPTOR_TERM = new Term( FLD_DESCRIPTOR, FLD_DESCRIPTOR_CONTENTS );

    private Directory indexDirectory;

    private File indexDirectoryFile;

    private String id;

    private boolean searchable;

    private String repositoryId;

    private File repository;

    private String repositoryUrl;

    private String indexUpdateUrl;

    private IndexReader indexReader;

    private NexusIndexSearcher indexSearcher;

    // disabled for now, see getReadOnlyIndexSearcher() method for explanation
    // private NexusIndexSearcher readOnlyIndexSearcher;

    private NexusIndexWriter indexWriter;

    private Date timestamp;

    private List<? extends IndexCreator> indexCreators;

    /**
     * Currently nexus-indexer knows only M2 reposes
     * <p>
     * XXX move this into a concrete Scanner implementation
     */
    private GavCalculator gavCalculator;

    private ReadWriteLock indexMaintenanceLock = new ReentrantReadWriteLock();

    private DefaultIndexingContext( String id,
                                    String repositoryId,
                                    File repository, //
                                    String repositoryUrl, String indexUpdateUrl,
                                    List<? extends IndexCreator> indexCreators, Directory indexDirectory,
                                    boolean reclaimIndex )
        throws UnsupportedExistingLuceneIndexException, IOException
    {
        this.id = id;

        this.searchable = true;

        this.repositoryId = repositoryId;

        this.repository = repository;

        this.repositoryUrl = repositoryUrl;

        this.indexUpdateUrl = indexUpdateUrl;

        this.indexReader = null;

        this.indexWriter = null;

        this.indexCreators = indexCreators;

        this.indexDirectory = indexDirectory;

        // eh?
        // Guice does NOT initialize these, and we have to do manually?
        // While in Plexus, all is well, but when in guice-shim,
        // these objects are still LazyHintedBeans or what not and IndexerFields are NOT registered!
        for ( IndexCreator indexCreator : indexCreators )
        {
            indexCreator.getIndexerFields();
        }

        this.gavCalculator = new M2GavCalculator();

        openAndWarmup();

        prepareIndex( reclaimIndex );
    }

    public DefaultIndexingContext( String id, String repositoryId, File repository, File indexDirectoryFile,
                                   String repositoryUrl, String indexUpdateUrl,
                                   List<? extends IndexCreator> indexCreators, boolean reclaimIndex )
        throws IOException, UnsupportedExistingLuceneIndexException
    {
        // TODO: use niofsdirectory
        this( id, repositoryId, repository, repositoryUrl, indexUpdateUrl, indexCreators,
            FSDirectory.getDirectory( indexDirectoryFile ), reclaimIndex );

        this.indexDirectoryFile = indexDirectoryFile;
    }

    public DefaultIndexingContext( String id, String repositoryId, File repository, Directory indexDirectory,
                                   String repositoryUrl, String indexUpdateUrl,
                                   List<? extends IndexCreator> indexCreators, boolean reclaimIndex )
        throws IOException, UnsupportedExistingLuceneIndexException
    {
        this( id, repositoryId, repository, repositoryUrl, indexUpdateUrl, indexCreators, indexDirectory, reclaimIndex );

        if ( indexDirectory instanceof FSDirectory )
        {
            this.indexDirectoryFile = ( (FSDirectory) indexDirectory ).getFile();
        }
    }

    public void lock()
    {
        indexMaintenanceLock.readLock().lock();
    }

    public void unlock()
    {
        indexMaintenanceLock.readLock().unlock();
    }

    public void lockExclusively()
    {
        indexMaintenanceLock.writeLock().lock();
    }

    public void unlockExclusively()
    {
        indexMaintenanceLock.writeLock().unlock();
    }

    public Directory getIndexDirectory()
    {
        return indexDirectory;
    }

    public File getIndexDirectoryFile()
    {
        return indexDirectoryFile;
    }

    private void prepareIndex( boolean reclaimIndex )
        throws IOException, UnsupportedExistingLuceneIndexException
    {
        if ( IndexReader.indexExists( indexDirectory ) )
        {
            try
            {
                // unlock the dir forcibly
                if ( IndexWriter.isLocked( indexDirectory ) )
                {
                    IndexWriter.unlock( indexDirectory );
                }

                checkAndUpdateIndexDescriptor( reclaimIndex );
            }
            catch ( IOException e )
            {
                if ( reclaimIndex )
                {
                    prepareCleanIndex( true );
                }
                else
                {
                    throw e;
                }
            }
        }
        else
        {
            prepareCleanIndex( false );
        }

        timestamp = IndexUtils.getTimestamp( indexDirectory );
    }

    private void prepareCleanIndex( boolean deleteExisting )
        throws IOException
    {
        if ( deleteExisting )
        {
            closeReaders();

            // unlock the dir forcibly
            if ( IndexWriter.isLocked( indexDirectory ) )
            {
                IndexWriter.unlock( indexDirectory );
            }

            deleteIndexFiles();

            openAndWarmup();
        }

        if ( StringUtils.isEmpty( getRepositoryId() ) )
        {
            throw new IllegalArgumentException( "The repositoryId cannot be null when creating new repository!" );
        }

        storeDescriptor();
    }

    private void checkAndUpdateIndexDescriptor( boolean reclaimIndex )
        throws IOException, UnsupportedExistingLuceneIndexException
    {
        if ( reclaimIndex )
        {
            // forcefully "reclaiming" the ownership of the index as ours
            storeDescriptor();
            return;
        }

        // check for descriptor if this is not a "virgin" index
        if ( getIndexReader().numDocs() > 0 )
        {
            Hits hits = getIndexSearcher().search( new TermQuery( DESCRIPTOR_TERM ) );

            if ( hits == null || hits.length() == 0 )
            {
                throw new UnsupportedExistingLuceneIndexException( "The existing index has no NexusIndexer descriptor" );
            }

            Document descriptor = hits.doc( 0 );

            if ( hits.length() != 1 )
            {
                storeDescriptor();
                return;
            }

            String[] h = StringUtils.split( descriptor.get( FLD_IDXINFO ), ArtifactInfo.FS );
            // String version = h[0];
            String repoId = h[1];

            // // compare version
            // if ( !VERSION.equals( version ) )
            // {
            // throw new UnsupportedExistingLuceneIndexException(
            // "The existing index has version [" + version + "] and not [" + VERSION + "] version!" );
            // }

            if ( getRepositoryId() == null )
            {
                repositoryId = repoId;
            }
            else if ( !getRepositoryId().equals( repoId ) )
            {
                throw new UnsupportedExistingLuceneIndexException( "The existing index is for repository " //
                    + "[" + repoId + "] and not for repository [" + getRepositoryId() + "]" );
            }
        }
    }

    private void storeDescriptor()
        throws IOException
    {
        Document hdr = new Document();

        hdr.add( new Field( FLD_DESCRIPTOR, FLD_DESCRIPTOR_CONTENTS, Field.Store.YES, Field.Index.NOT_ANALYZED ) );

        hdr.add( new Field( FLD_IDXINFO, VERSION + ArtifactInfo.FS + getRepositoryId(), Field.Store.YES, Field.Index.NO ) );

        IndexWriter w = getIndexWriter();

        w.updateDocument( DESCRIPTOR_TERM, hdr );

        w.commit();
    }

    private void deleteIndexFiles()
        throws IOException
    {
        String[] names = indexDirectory.list();

        if ( names != null )
        {
            for ( int i = 0; i < names.length; i++ )
            {
                indexDirectory.deleteFile( names[i] );
            }
        }

        IndexUtils.deleteTimestamp( indexDirectory );
    }

    public boolean isSearchable()
    {
        return searchable;
    }

    public void setSearchable( boolean searchable )
    {
        this.searchable = searchable;
    }

    public String getId()
    {
        return id;
    }

    public void updateTimestamp()
        throws IOException
    {
        updateTimestamp( false );
    }

    public void updateTimestamp( boolean save )
        throws IOException
    {
        updateTimestamp( save, new Date() );
    }

    public void updateTimestamp( boolean save, Date timestamp )
        throws IOException
    {
        this.timestamp = timestamp;

        if ( save )
        {
            IndexUtils.updateTimestamp( indexDirectory, getTimestamp() );
        }
    }

    public Date getTimestamp()
    {
        return timestamp;
    }

    public int getSize()
        throws IOException
    {
        return getIndexReader().numDocs();
    }

    public String getRepositoryId()
    {
        return repositoryId;
    }

    public File getRepository()
    {
        return repository;
    }

    public String getRepositoryUrl()
    {
        return repositoryUrl;
    }

    public String getIndexUpdateUrl()
    {
        if ( repositoryUrl != null )
        {
            if ( indexUpdateUrl == null || indexUpdateUrl.trim().length() == 0 )
            {
                return repositoryUrl + ( repositoryUrl.endsWith( "/" ) ? "" : "/" ) + INDEX_DIRECTORY;
            }
        }
        return indexUpdateUrl;
    }

    public Analyzer getAnalyzer()
    {
        return new NexusAnalyzer();
    }

    protected void openAndWarmup()
        throws IOException
    {
        indexMaintenanceLock.writeLock().lock();

        try
        {
            // IndexWriter (close)
            if ( indexWriter != null )
            {
                indexWriter.close();
            }
            // IndexSearcher (close only, since we did supply this.indexReader explicitly)
            if ( indexSearcher != null )
            {
                indexSearcher.close();
            }
            // IndexReader
            if ( indexReader != null )
            {
                indexReader.close();
            }

            // IndexWriter open
            final boolean create = !IndexReader.indexExists( indexDirectory );

            indexWriter = new NexusIndexWriter( getIndexDirectory(), new NexusAnalyzer(), create );

            indexWriter.setRAMBufferSizeMB( 2 );

            indexWriter.setMergeScheduler( new SerialMergeScheduler() );

            openAndWarmupReaders( false );
        }
        finally
        {
            indexMaintenanceLock.writeLock().unlock();
        }
    }

    protected void openAndWarmupReaders( boolean justTry )
        throws IOException
    {
        if ( indexReader != null && indexReader.isCurrent() )
        {
            return;
        }

        if ( justTry )
        {
            if ( !indexMaintenanceLock.writeLock().tryLock() )
            {
                return;
            }
        }
        else
        {
            indexMaintenanceLock.writeLock().lock();
        }

        try
        {
            // IndexSearcher (close only, since we did supply this.indexReader explicitly)
            if ( indexSearcher != null )
            {
                indexSearcher.close();
            }
            // IndexReader
            if ( indexReader != null )
            {
                indexReader.close();
            }

            // IndexReader open
            indexReader = IndexReader.open( indexDirectory, true );

            // IndexSearcher open
            indexSearcher = new NexusIndexSearcher( this );

            // warm up
            warmUp();

            // mark readers as refreshed
            hasCommits = false;
        }
        finally
        {
            indexMaintenanceLock.writeLock().unlock();
        }
    }

    protected void warmUp()
        throws IOException
    {
        try
        {
            // TODO: figure this out better
            getIndexSearcher().search( new TermQuery( new Term( "g", "org" ) ), 1000 );
        }
        catch ( IOException e )
        {
            close( false );

            throw e;
        }
    }

    public IndexWriter getIndexWriter()
        throws IOException
    {
        lock();

        try
        {
            return indexWriter;
        }
        finally
        {
            unlock();
        }
    }

    public IndexReader getIndexReader()
        throws IOException
    {
        lock();

        try
        {
            return indexReader;
        }
        finally
        {
            unlock();
        }
    }

    public IndexSearcher getIndexSearcher()
        throws IOException
    {
        lock();

        try
        {
            return indexSearcher;
        }
        finally
        {
            unlock();
        }
    }

    private volatile boolean hasCommits = false;

    public void commit()
        throws IOException
    {
        // TODO: detect is writer "dirty"?
        if ( true )
        {
            lock();

            try
            {
                IndexWriter w = getIndexWriter();

                try
                {
                    w.commit();

                    hasCommits = true;
                }
                catch ( CorruptIndexException e )
                {
                    close( false );

                    throw e;
                }
                catch ( IOException e )
                {
                    close( false );

                    throw e;
                }
            }
            finally
            {
                unlock();
            }

            // TODO: define some treshold or requirement
            // for reopening readers (is expensive)
            // For example: by inserting 1 record among 1M, do we really want to reopen?
            if ( true )
            {
                openAndWarmupReaders( true );
            }
        }
    }

    public void rollback()
        throws IOException
    {
        // detect is writer "dirty"?
        if ( true )
        {
            lockExclusively();

            try
            {
                IndexWriter w = getIndexWriter();

                try
                {
                    w.rollback();
                }
                catch ( CorruptIndexException e )
                {
                    close( false );

                    throw e;
                }
                catch ( IOException e )
                {
                    close( false );

                    throw e;
                }
            }
            finally
            {
                unlockExclusively();
            }
        }
    }

    public void optimize()
        throws CorruptIndexException, IOException
    {
        lockExclusively();

        try
        {
            IndexWriter w = getIndexWriter();

            try
            {
                w.optimize();

                commit();
            }
            catch ( CorruptIndexException e )
            {
                close( false );

                throw e;
            }
            catch ( IOException e )
            {
                close( false );

                throw e;
            }
        }
        finally
        {
            unlockExclusively();
        }
    }

    public void close( boolean deleteFiles )
        throws IOException
    {
        lockExclusively();

        try
        {
            if ( indexDirectory != null )
            {
                IndexUtils.updateTimestamp( indexDirectory, getTimestamp() );

                closeReaders();

                if ( deleteFiles )
                {
                    deleteIndexFiles();
                }

                indexDirectory.close();
            }

            // TODO: this will prevent from reopening them, but needs better solution
            indexDirectory = null;
        }
        finally
        {
            unlockExclusively();
        }
    }

    public void purge()
        throws IOException
    {
        lockExclusively();

        try
        {
            closeReaders();

            deleteIndexFiles();

            openAndWarmup();

            try
            {
                prepareIndex( true );
            }
            catch ( UnsupportedExistingLuceneIndexException e )
            {
                // just deleted it
            }

            rebuildGroups();

            updateTimestamp( true, null );
        }
        finally
        {
            unlockExclusively();
        }
    }

    public void replace( Directory directory )
        throws IOException
    {
        lockExclusively();

        try
        {
            Date ts = IndexUtils.getTimestamp( directory );

            closeReaders();

            deleteIndexFiles();

            Directory.copy( directory, indexDirectory, false );
            // We do it manually here, since we do want delete to happen 1st
            // IndexUtils.copyDirectory( directory, indexDirectory );

            openAndWarmup();

            // reclaim the index as mine
            storeDescriptor();

            updateTimestamp( true, ts );

            optimize();
        }
        finally
        {
            unlockExclusively();
        }
    }

    public void merge( Directory directory )
        throws IOException
    {
        merge( directory, null );
    }

    public void merge( Directory directory, DocumentFilter filter )
        throws IOException
    {
        lockExclusively();

        try
        {
            IndexWriter w = getIndexWriter();

            IndexSearcher s = getIndexSearcher();

            IndexReader r = IndexReader.open( directory, true );

            try
            {
                int numDocs = r.maxDoc();

                for ( int i = 0; i < numDocs; i++ )
                {
                    if ( r.isDeleted( i ) )
                    {
                        continue;
                    }

                    Document d = r.document( i );

                    if ( filter != null && !filter.accept( d ) )
                    {
                        continue;
                    }

                    String uinfo = d.get( ArtifactInfo.UINFO );

                    if ( uinfo != null )
                    {
                        Hits hits = s.search( new TermQuery( new Term( ArtifactInfo.UINFO, uinfo ) ) );

                        if ( hits.length() == 0 )
                        {
                            w.addDocument( IndexUtils.updateDocument( d, this, false ) );
                        }
                    }
                    else
                    {
                        String deleted = d.get( ArtifactInfo.DELETED );

                        if ( deleted != null )
                        {
                            // Deleting the document loses history that it was delete,
                            // so incrementals wont work. Therefore, put the delete
                            // document in as well
                            w.deleteDocuments( new Term( ArtifactInfo.UINFO, deleted ) );
                            w.addDocument( d );
                        }
                    }
                }

            }
            finally
            {
                r.close();

                commit();
            }

            rebuildGroups();

            Date mergedTimestamp = IndexUtils.getTimestamp( directory );

            if ( getTimestamp() != null && mergedTimestamp != null && mergedTimestamp.after( getTimestamp() ) )
            {
                // we have both, keep the newest
                updateTimestamp( true, mergedTimestamp );
            }
            else
            {
                updateTimestamp( true );
            }

            optimize();
        }
        finally
        {
            unlockExclusively();
        }
    }

    private void closeReaders()
        throws CorruptIndexException, IOException
    {
        if ( indexWriter != null )
        {
            if ( !indexWriter.isClosed() )
            {
                indexWriter.close();
            }

            indexWriter = null;
        }
        if ( indexSearcher != null )
        {
            indexSearcher.close();

            indexSearcher = null;
        }
        if ( indexReader != null )
        {
            indexReader.close();

            indexReader = null;
        }
    }

    public GavCalculator getGavCalculator()
    {
        return gavCalculator;
    }

    public List<IndexCreator> getIndexCreators()
    {
        return Collections.unmodifiableList( indexCreators );
    }

    // groups

    public void rebuildGroups()
        throws IOException
    {
        lockExclusively();

        try
        {
            IndexUtils.rebuildGroups( this );
        }
        finally
        {
            unlockExclusively();
        }
    }

    public Set<String> getAllGroups()
        throws IOException
    {
        lock();

        try
        {
            return IndexUtils.getAllGroups( this );
        }
        finally
        {
            unlock();
        }
    }

    public void setAllGroups( Collection<String> groups )
        throws IOException
    {
        lockExclusively();

        try
        {
            IndexUtils.setAllGroups( this, groups );
        }
        finally
        {
            unlockExclusively();
        }
    }

    public Set<String> getRootGroups()
        throws IOException
    {
        lock();

        try
        {
            return IndexUtils.getRootGroups( this );
        }
        finally
        {
            unlock();
        }
    }

    public void setRootGroups( Collection<String> groups )
        throws IOException
    {
        lockExclusively();

        try
        {
            IndexUtils.setRootGroups( this, groups );
        }
        finally
        {
            unlockExclusively();
        }
    }

    @Override
    public String toString()
    {
        return id + " : " + timestamp;
    }

}
