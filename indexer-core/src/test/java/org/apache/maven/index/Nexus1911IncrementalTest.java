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
package org.apache.maven.index;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import junit.framework.Assert;

import org.apache.maven.index.NexusIndexer;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.packer.IndexPacker;
import org.apache.maven.index.packer.IndexPackingRequest;
import org.codehaus.plexus.util.FileUtils;

public class Nexus1911IncrementalTest
    extends AbstractIndexCreatorHelper
{
    NexusIndexer indexer;

    IndexingContext context;

    IndexingContext reindexedContext;

    IndexPacker packer;

    File indexDir;

    File reposTargetDir;

    @Override
    protected void setUp()
        throws Exception
    {
        super.setUp();

        indexer = lookup( NexusIndexer.class );
        packer = lookup( IndexPacker.class );

        indexDir = super.getDirectory( "index/nexus-1911" );

        File reposSrcDir = new File( getBasedir(), "src/test/nexus-1911" );
        this.reposTargetDir = super.getDirectory( "repos/nexus-1911" );

        FileUtils.copyDirectoryStructure( reposSrcDir, reposTargetDir );

        File repo = new File( reposTargetDir, "repo" );
        repo.mkdirs();
        reindexedContext =
            context = indexer.addIndexingContext( "test", "test", repo, indexDir, null, null, DEFAULT_CREATORS );
        indexer.scan( context );
    }

    @Override
    protected void tearDown()
        throws Exception
    {
        indexer.removeIndexingContext( context, true );
        super.deleteDirectory( this.reposTargetDir );
        super.deleteDirectory( this.indexDir );
        super.tearDown();
    }

    public void testNoIncremental()
        throws Exception
    {
        IndexPackingRequest request = new IndexPackingRequest( context, indexDir );
        request.setCreateIncrementalChunks( true );
        packer.packIndex( request );

        Set<String> filenames = getFilenamesFromFiles( indexDir.listFiles() );
        Properties props = getPropertiesFromFiles( indexDir.listFiles() );

        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".zip" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".properties" ) );
        Assert.assertFalse( filenames.contains( IndexingContext.INDEX_FILE + ".1.gz" ) );
        Assert.assertFalse( filenames.contains( IndexingContext.INDEX_FILE + ".2.gz" ) );

        Assert.assertNotNull( props );

        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "0" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "1" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "2" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "3" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "4" ) );
        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_COUNTER ), "0" );
        Assert.assertNotNull( props.getProperty( IndexingContext.INDEX_CHAIN_ID ) );
    }

    public void test1Incremental()
        throws Exception
    {
        IndexPackingRequest request = new IndexPackingRequest( context, indexDir );
        request.setCreateIncrementalChunks( true );
        packer.packIndex( request );

        copyRepoContentsAndReindex( new File( getBasedir(), "src/test/nexus-1911/repo-inc-1" ), request );

        Set<String> filenames = getFilenamesFromFiles( indexDir.listFiles() );
        Properties props = getPropertiesFromFiles( indexDir.listFiles() );

        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".zip" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".properties" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".1.gz" ) );
        Assert.assertFalse( filenames.contains( IndexingContext.INDEX_FILE + ".2.gz" ) );

        Assert.assertNotNull( props );

        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "0" ), "1" );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "1" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "2" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "3" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "4" ) );
        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_COUNTER ), "1" );
        Assert.assertNotNull( props.getProperty( IndexingContext.INDEX_CHAIN_ID ) );
    }

    public void test2Incremental()
        throws Exception
    {
        IndexPackingRequest request = new IndexPackingRequest( context, indexDir );
        request.setCreateIncrementalChunks( true );
        packer.packIndex( request );

        copyRepoContentsAndReindex( new File( getBasedir(), "src/test/nexus-1911/repo-inc-1" ), request );
        copyRepoContentsAndReindex( new File( getBasedir(), "src/test/nexus-1911/repo-inc-2" ), request );

        Set<String> filenames = getFilenamesFromFiles( indexDir.listFiles() );
        Properties props = getPropertiesFromFiles( indexDir.listFiles() );

        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".zip" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".properties" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".1.gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".2.gz" ) );
        Assert.assertFalse( filenames.contains( IndexingContext.INDEX_FILE + ".3.gz" ) );

        Assert.assertNotNull( props );

        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "0" ), "2" );
        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "1" ), "1" );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "2" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "3" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "4" ) );
        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_COUNTER ), "2" );
        Assert.assertNotNull( props.getProperty( IndexingContext.INDEX_CHAIN_ID ) );
    }

    public void test3Incremental()
        throws Exception
    {
        IndexPackingRequest request = new IndexPackingRequest( context, indexDir );
        request.setCreateIncrementalChunks( true );
        packer.packIndex( request );

        copyRepoContentsAndReindex( new File( getBasedir(), "src/test/nexus-1911/repo-inc-1" ), request );
        copyRepoContentsAndReindex( new File( getBasedir(), "src/test/nexus-1911/repo-inc-2" ), request );
        copyRepoContentsAndReindex( new File( getBasedir(), "src/test/nexus-1911/repo-inc-3" ), request );

        Set<String> filenames = getFilenamesFromFiles( indexDir.listFiles() );
        Properties props = getPropertiesFromFiles( indexDir.listFiles() );

        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".zip" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".properties" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".1.gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".2.gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".3.gz" ) );

        Assert.assertNotNull( props );

        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "0" ), "3" );
        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "1" ), "2" );
        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "2" ), "1" );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "3" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "4" ) );
        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_COUNTER ), "3" );
        Assert.assertNotNull( props.getProperty( IndexingContext.INDEX_CHAIN_ID ) );
    }

    public void testMaxChunks()
        throws Exception
    {
        IndexPackingRequest request = new IndexPackingRequest( context, indexDir );
        request.setCreateIncrementalChunks( true );
        request.setMaxIndexChunks( 3 );
        packer.packIndex( request );

        copyRepoContentsAndReindex( new File( getBasedir(), "src/test/nexus-1911/repo-inc-1" ), request );
        copyRepoContentsAndReindex( new File( getBasedir(), "src/test/nexus-1911/repo-inc-2" ), request );
        copyRepoContentsAndReindex( new File( getBasedir(), "src/test/nexus-1911/repo-inc-3" ), request );
        copyRepoContentsAndReindex( new File( getBasedir(), "src/test/nexus-1911/repo-inc-4" ), request );

        Set<String> filenames = getFilenamesFromFiles( indexDir.listFiles() );
        Properties props = getPropertiesFromFiles( indexDir.listFiles() );

        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".zip" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".properties" ) );
        Assert.assertFalse( filenames.contains( IndexingContext.INDEX_FILE + ".1.gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".2.gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".3.gz" ) );
        Assert.assertTrue( filenames.contains( IndexingContext.INDEX_FILE + ".4.gz" ) );

        Assert.assertNotNull( props );

        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "0" ), "4" );
        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "1" ), "3" );
        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "2" ), "2" );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "3" ) );
        Assert.assertNull( props.getProperty( IndexingContext.INDEX_CHUNK_PREFIX + "4" ) );
        Assert.assertEquals( props.getProperty( IndexingContext.INDEX_CHUNK_COUNTER ), "4" );
        Assert.assertNotNull( props.getProperty( IndexingContext.INDEX_CHAIN_ID ) );
    }

    private void copyRepoContentsAndReindex( File src, IndexPackingRequest request )
        throws Exception
    {
        File reposTargetDir = new File( getBasedir(), "target/repos/nexus-1911/repo" );

        FileUtils.copyDirectoryStructure( src, reposTargetDir );

        indexer.scan( reindexedContext );

        packer.packIndex( request );
    }

    private Set<String> getFilenamesFromFiles( File[] files )
    {
        Set<String> filenames = new HashSet<String>();

        for ( int i = 0; i < files.length; i++ )
        {
            filenames.add( files[i].getName() );
        }

        return filenames;
    }

    private Properties getPropertiesFromFiles( File[] files )
        throws Exception
    {
        Properties props = new Properties();
        File propertyFile = null;

        for ( int i = 0; i < files.length; i++ )
        {
            if ( ( IndexingContext.INDEX_FILE + ".properties" ).equalsIgnoreCase( files[i].getName() ) )
            {
                propertyFile = files[i];
                break;
            }
        }

        FileInputStream fis = null;

        try
        {
            fis = new FileInputStream( propertyFile );
            props.load( fis );
        }
        finally
        {
            fis.close();
        }

        return props;
    }
}
