package models;

import java.io.File;
import java.io.IOException;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.Label;

public class EmbeddedNeo4j
{
    private static final String DB_PATH = "graph-db";

    GraphDatabaseService graphDb;
    Node currentBlock;
    private static Index<Node> nodeIndex;

    private static enum RelTypes implements RelationshipType
    {
        FOLLOWS,
        CONTAINS,
        EMITS,
        SUPPLIES,
        IS_SENT_TO
    }

    public void dropDb() throws IOException
    {
        FileUtils.deleteRecursively( new File( DB_PATH ) );
    }

    public void createDb()
    {
        graphDb = new GraphDatabaseFactory().newEmbeddedDatabase( DB_PATH );
        registerShutdownHook( graphDb );
    }

    public void testInsert()
    {
        // START SNIPPET: addUsers
        try ( Transaction tx = graphDb.beginTx() )
        {
            nodeIndex = graphDb.index().forNodes( "nodes" );

            for ( int id = 0; id < 5; id++ )
            {
                currentBlock = createAndIndexBlock(id, currentBlock);
            }

            tx.success();
        }
    }

    private Node createAndIndexBlock( final Integer blockId, Node parentBlock )
    {
        Label label = DynamicLabel.label( "Block" );
        Node node = graphDb.createNode(label);
        node.setProperty( "hash", blockId );
        nodeIndex.add( node, "hash", blockId );

        if(parentBlock!=null){
            node.createRelationshipTo( parentBlock, RelTypes.FOLLOWS );
        }

        return node;
    }

    public void shutDown()
    {
        System.out.println();
        System.out.println( "Shutting down database ..." );
        graphDb.shutdown();
    }

    private static void registerShutdownHook( final GraphDatabaseService graphDb )
    {
        // Registers a shutdown hook for the Neo4j instance so that it
        // shuts down nicely when the VM exits (even if you "Ctrl-C" the
        // running application).
        Runtime.getRuntime().addShutdownHook( new Thread()
        {
            @Override
            public void run()
            {
                graphDb.shutdown();
            }
        } );
    }
}