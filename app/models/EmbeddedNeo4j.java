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
import org.neo4j.graphdb.ResourceIterator;

public class EmbeddedNeo4j
{
    public static void insertBlock(GraphDatabaseService graphDb, String queryString, String blockHash)
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            Index<Node> nodeIndex = graphDb.index().forNodes( "nodes" );
            ResourceIterator<Node> resultIterator = graphDb.execute( queryString ).columnAs( "b" );
            Node node = resultIterator.next();

            nodeIndex.add( node, "hash", blockHash );

            tx.success();
        }
    }

    public static void insertTransaction(GraphDatabaseService graphDb, String queryString, String txHash)
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            Index<Node> nodeIndex = graphDb.index().forNodes( "nodes" );
            ResourceIterator<Node> resultIterator = graphDb.execute( queryString ).columnAs( "tx" );
            Node node = resultIterator.next();

            nodeIndex.add( node, "hash", txHash );

            tx.success();
        }
    }

    public static String getUnprocessedTransaction(GraphDatabaseService graphDb)
    {
        try ( Transaction tx = graphDb.beginTx() )
        {
            Index<Node> nodeIndex = graphDb.index().forNodes( "nodes" );
            String queryString = "MATCH (tx:Transaction) WHERE NOT HAS (tx.lock_time) AND NOT(tx.hash IN ['97ddfbbae6be97fd6cdf3e7ca13232a3afff2353e29badfab7f73011edd4ced9']) RETURN tx";
            ResourceIterator<Node> resultIterator = graphDb.execute( queryString ).columnAs( "tx" );
            Node node = resultIterator.next();

            tx.success();
            String hash = (String) node.getProperty("hash");
            return hash;
        }
    }

    /*
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
                currentBlock = mergeAndIndexBlock(id, currentBlock);
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

    private Node mergeAndIndexBlock( final Integer blockId, Node parentBlock )
    {
        ResourceIterator<Node> resultIterator = null;

        String queryString = "MERGE (b:Block { hash: '"+blockId+"' }) ON CREATE SET b.height = "+blockId+", b.time = "+blockId+", b.main_chain = "+blockId+" ";

        if(parentBlock!=null){
            queryString = "MATCH (prevBlock:Block { hash: '"+parentBlock.getProperty("hash")+"' }) "+queryString+" MERGE (b)-[:FOLLOWS]->(prevBlock) ";
        }

        queryString += "RETURN b";

        resultIterator = graphDb.execute( queryString ).columnAs( "b" );
        Node node = resultIterator.next();

        nodeIndex.add( node, "hash", blockId );

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
    */
}