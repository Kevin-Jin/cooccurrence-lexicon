package in.kevinj.analytics.networks;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.gephi.data.attributes.api.AttributeColumn;
import org.gephi.data.attributes.api.AttributeController;
import org.gephi.data.attributes.api.AttributeModel;
import org.gephi.data.attributes.api.AttributeType;
import org.gephi.data.attributes.type.StringList;
import org.gephi.filters.api.FilterController;
import org.gephi.filters.api.Query;
import org.gephi.filters.api.Range;
import org.gephi.filters.plugin.attribute.AttributeRangeBuilder;
import org.gephi.filters.plugin.edge.EdgeWeightBuilder;
import org.gephi.filters.plugin.graph.DegreeRangeBuilder;
import org.gephi.filters.plugin.partition.PartitionCountBuilder;
import org.gephi.graph.api.GraphController;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.Node;
import org.gephi.io.exporter.api.ExportController;
import org.gephi.io.exporter.spi.GraphExporter;
import org.gephi.io.importer.api.Container;
import org.gephi.io.importer.api.ContainerFactory;
import org.gephi.io.importer.api.ContainerLoader;
import org.gephi.io.importer.api.EdgeDefault;
import org.gephi.io.importer.api.EdgeDraft;
import org.gephi.io.importer.api.EdgeDraft.EdgeType;
import org.gephi.io.importer.api.ImportController;
import org.gephi.io.importer.api.Issue;
import org.gephi.io.importer.api.NodeDraft;
import org.gephi.io.importer.api.Report;
import org.gephi.io.processor.plugin.DefaultProcessor;
import org.gephi.layout.plugin.forceAtlas2.ForceAtlas2;
import org.gephi.layout.plugin.forceAtlas2.ForceAtlas2Builder;
import org.gephi.layout.plugin.fruchterman.FruchtermanReingold;
import org.gephi.layout.plugin.fruchterman.FruchtermanReingoldBuilder;
import org.gephi.partition.api.Partition;
import org.gephi.partition.api.PartitionController;
import org.gephi.partition.plugin.NodeColorTransformer;
import org.gephi.preview.api.PreviewController;
import org.gephi.preview.api.PreviewModel;
import org.gephi.preview.api.PreviewProperty;
import org.gephi.project.api.ProjectController;
import org.gephi.project.api.Workspace;
import org.gephi.ranking.api.Ranking;
import org.gephi.ranking.api.RankingController;
import org.gephi.ranking.api.Transformer;
import org.gephi.ranking.plugin.transformer.AbstractSizeTransformer;
import org.gephi.statistics.plugin.ConnectedComponents;
import org.gephi.statistics.plugin.Modularity;
import org.gephi.statistics.plugin.WeightedDegree;
import org.openide.util.Lookup;

import com.sun.xml.internal.txw2.output.IndentingXMLStreamWriter;

public class NetworkAnalyzer {
	private static final Logger LOG = Logger.getLogger(NetworkAnalyzer.class.getName());

	private static final int MIN_SENTENCES = 2;
	private static final float MIN_PMI = 0.5f;
	private static final int MIN_COMPONENT_SIZE = 3;
	private static final double RESOLUTION = 0.5;

	// Must also at least override print(String), all overloads of println(),
	// all overloads of format(), and append(CharSequence, int, int)
	private static class PrintlnToLog extends PrintStream {
		private final PrintStream orig;

		private PrintlnToLog(PrintStream orig) {
			super(orig, true);
			this.orig = orig;
		}

		/**
		 * {@inheritDoc}
		 */
	    @Override
	    public void println(String s) {
	    	LOG.log(Level.INFO, s);
	    }

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void close() {
			System.setOut(orig);
		}

		public static PrintlnToLog intercept() {
			PrintlnToLog newStdout = new PrintlnToLog(System.out);
			System.setOut(newStdout);
			return newStdout;
		}
	}

	private static GraphModel generateGraph(Set<ProperNounProform.NamedEntity> uniqueNodes, SortedSet<CoOccurrenceExtractor.EntityPair> network, Workspace workspace) {
		GraphModel graphModel = Lookup.getDefault().lookup(GraphController.class).getModel();

		Container container;
		Report r = new Report();
		try {
			container = Lookup.getDefault().lookup(ContainerFactory.class).newContainer();
			container.setReport(r);
			container.setAllowAutoNode(false);
			ContainerLoader loader = container.getLoader();
			loader.setEdgeDefault(EdgeDefault.UNDIRECTED);
			AttributeModel model = loader.getAttributeModel();
			AttributeColumn aliasCol = model.getNodeTable().addColumn("aliases", AttributeType.LIST_STRING);
			AttributeColumn sentencesCol = model.getEdgeTable().addColumn("sentences", AttributeType.INT);
			AttributeColumn documentsCol = model.getEdgeTable().addColumn("documents", AttributeType.INT);

			for (ProperNounProform.NamedEntity node : uniqueNodes) {
				NodeDraft graphNode = loader.factory().newNodeDraft();
				graphNode.setId(node.key);
				graphNode.setLabel(node.key);
				graphNode.addAttributeValue(aliasCol, new StringList(node.aliases.toArray(new String[node.aliases.size()])));
				loader.addNode(graphNode);
			}

			for (CoOccurrenceExtractor.EntityPair edge : network) {
				EdgeDraft graphEdge = loader.factory().newEdgeDraft();
				graphEdge.setSource(loader.getNode(edge.a.key));
				graphEdge.setTarget(loader.getNode(edge.b.key));
				graphEdge.setWeight((float) edge.relationship);
				graphEdge.addAttributeValue(sentencesCol, Integer.valueOf(edge.sentences));
				graphEdge.addAttributeValue(documentsCol, Integer.valueOf(edge.documents));
				graphEdge.setType(EdgeType.UNDIRECTED);
				loader.addEdge(graphEdge);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			return null;
		} finally {
			for (Issue i : r.getIssues()) {
				Level l;
				switch (i.getLevel()) {
					case INFO:
						l = Level.INFO;
						break;
					case WARNING:
						l = Level.WARNING;
						break;
					case SEVERE:
					case CRITICAL:
						l = Level.SEVERE;
						break;
					default:
						l = null;
						break;
				}
				Throwable t = i.getThrowable();
				if (t != null)
					LOG.log(l, i.getMessage(), t);
				else
					LOG.log(l, i.getMessage());
			}
		}

		ImportController importController = Lookup.getDefault().lookup(ImportController.class);
		importController.process(container, new DefaultProcessor(), workspace);

		return graphModel;
	}

	/**
	 * Deletes all edges where the two names are mentioned together in fewer
	 * than {@code threshold} sentences.
	 *
	 * @param graphModel
	 * @param threshold
	 * @return
	 */
	private static Query filterByInstances(GraphModel graphModel, int threshold) {
		AttributeModel attributeModel = Lookup.getDefault().lookup(AttributeController.class).getModel();
		FilterController filterController = Lookup.getDefault().lookup(FilterController.class);

		AttributeColumn compCol = attributeModel.getEdgeTable().getColumn("sentences");
		AttributeRangeBuilder.AttributeRangeFilter filter = new AttributeRangeBuilder.AttributeRangeFilter(compCol);
		filter.setRange(new Range(Integer.valueOf(threshold), Integer.valueOf(Integer.MAX_VALUE)));

		return filterController.createQuery(filter);
	}

	/**
	 * Deletes all edges with a weight below the passed {@code threshold}.
	 *
	 * @param graphModel
	 * @param threshold
	 * @return
	 */
	private static Query filterByStrength(GraphModel graphModel, float threshold) {
		FilterController filterController = Lookup.getDefault().lookup(FilterController.class);

		EdgeWeightBuilder.EdgeWeightFilter filter = (EdgeWeightBuilder.EdgeWeightFilter) new EdgeWeightBuilder().getFilter();
		filter.setRange(new Range(Float.valueOf(threshold), Float.valueOf(Float.POSITIVE_INFINITY)));

		return filterController.createQuery(filter);
	}

	/**
	 * Delete all nodes that have no edges to any other node.
	 *
	 * @return
	 */
	private static Query deleteIsolatedVertices(GraphModel graphModel) {
		FilterController filterController = Lookup.getDefault().lookup(FilterController.class);

		DegreeRangeBuilder.DegreeRangeFilter filter = new DegreeRangeBuilder().getFilter();
		filter.setRange(new Range(Integer.valueOf(1), Integer.valueOf(Integer.MAX_VALUE)));

		return filterController.createQuery(filter);
	}

	/**
	 * Define small components as connected components having a size less than
	 * {@code threshold}, i.e. there are fewer than {@code threshold} names in
	 * the community and none of the names in the community have a path to a
	 * name outside of the community.
	 *
	 * @param graphModel
	 * @param threshold
	 * @return
	 */
	private static Query deleteSmallComponents(GraphModel graphModel, int threshold) {
		AttributeModel attributeModel = Lookup.getDefault().lookup(AttributeController.class).getModel();
		PartitionController partitionController = Lookup.getDefault().lookup(PartitionController.class);
		FilterController filterController = Lookup.getDefault().lookup(FilterController.class);

		ConnectedComponents components = new ConnectedComponents();
		components.execute(graphModel, attributeModel);

		AttributeColumn compCol = attributeModel.getNodeTable().getColumn(ConnectedComponents.WEAKLY);
		Partition<?> part = partitionController.buildPartition(compCol, graphModel.getUndirectedGraphVisible());
		PartitionCountBuilder.PartitionCountFilter filter = new PartitionCountBuilder.PartitionCountFilter(part);
		filter.setRange(new Range(Integer.valueOf(threshold), Integer.valueOf(Integer.MAX_VALUE)));

		return filterController.createQuery(filter);
	}

	private static GraphModel applyFilters(GraphModel graphModel, int minSentences, float minWeight, int minComp) {
		FilterController filterController = Lookup.getDefault().lookup(FilterController.class);

		Query edgeFilter1 = filterByInstances(graphModel, minSentences);
		graphModel.setVisibleView(filterController.filter(edgeFilter1));

		Query edgeFilter2 = filterByStrength(graphModel, minWeight);
		filterController.setSubQuery(edgeFilter2, edgeFilter1);
		graphModel.setVisibleView(filterController.filter(edgeFilter2));

		Query nodeFilter = deleteIsolatedVertices(graphModel);
		filterController.setSubQuery(nodeFilter, edgeFilter2);
		graphModel.setVisibleView(filterController.filter(nodeFilter));

		Query compFilter = deleteSmallComponents(graphModel, minComp);
		filterController.setSubQuery(compFilter, nodeFilter);
		graphModel.setVisibleView(filterController.filter(compFilter));

		filterController.getModel().getLibrary().saveQuery(edgeFilter1);
		filterController.getModel().getLibrary().saveQuery(edgeFilter2);
		filterController.getModel().getLibrary().saveQuery(nodeFilter);
		filterController.getModel().getLibrary().saveQuery(compFilter);
		//filterController.exportToNewWorkspace(edgeFilter1);
		//filterController.exportToNewWorkspace(edgeFilter2);
		//filterController.exportToNewWorkspace(nodeFilter);
		//filterController.exportToNewWorkspace(compFilter);

		return graphModel;
	}

	/**
	 * Detects communities using the Louvain method.
	 *
	 * @param graphModel
	 * @param adjustment - Lower to get more communities (smaller ones) and
	 *                     higher to get fewer communities (larger ones).
	 *                     Baseline is 1.0d.
	 * @return
	 */
	private static GraphModel colorByClusters(GraphModel graphModel, double adjustment) {
		AttributeModel attributeModel = Lookup.getDefault().lookup(AttributeController.class).getModel();
		PartitionController partitionController = Lookup.getDefault().lookup(PartitionController.class);

		// Run modularity algorithm - community detection
		Modularity modularity = new Modularity();
		modularity.setRandom(true);
		modularity.setResolution(adjustment);
		modularity.execute(graphModel, attributeModel);

		// Partition with 'modularity_class', just created by Modularity algorithm
		AttributeColumn modColumn = attributeModel.getNodeTable().getColumn(Modularity.MODULARITY_CLASS);
		Partition<?> part = partitionController.buildPartition(modColumn, graphModel.getUndirectedGraphVisible());
		LOG.log(Level.INFO, part.getPartsCount() + " communities found");
		NodeColorTransformer nodeColorTransformer2 = new NodeColorTransformer();
		nodeColorTransformer2.randomizeColors(part);
		partitionController.transform(part, nodeColorTransformer2);
		partitionController.setSelectedPartition(part);

		// TODO: color all modularity classes in the same component one after
		// another to maximize contrast of adjacent communities

		return graphModel;
	}

	/**
	 * Increases the visibility of nodes that have more connections to others.
	 *
	 * @param graphModel
	 * @return
	 */
	private static GraphModel growByWeightedDegree(GraphModel graphModel) {
		AttributeModel attributeModel = Lookup.getDefault().lookup(AttributeController.class).getModel();
		RankingController rankingController = Lookup.getDefault().lookup(RankingController.class);

		WeightedDegree degree = new WeightedDegree();
		degree.execute(graphModel, attributeModel);

		Ranking<?> degreeRanking = rankingController.getModel().getRanking(Ranking.NODE_ELEMENT, /*Ranking.DEGREE_RANKING*/ WeightedDegree.WDEGREE);
		AbstractSizeTransformer<?> sizeTransformer = (AbstractSizeTransformer<?>) rankingController.getModel().getTransformer(Ranking.NODE_ELEMENT, Transformer.RENDERABLE_SIZE);
		sizeTransformer.setMinSize(4);
		sizeTransformer.setMaxSize(16);
		rankingController.transform(degreeRanking, sizeTransformer);

		return graphModel;
	}

	/**
	 * Labels using the community ID followed by a sequential community specific
	 * ID. The mappings to names can be found in the XML output of this program.
	 *
	 * @param graphModel
	 * @return
	 */
	private static GraphModel labelNodes(GraphModel graphModel) {
		AttributeModel attributeModel = Lookup.getDefault().lookup(AttributeController.class).getModel();
		AttributeColumn modColumn = attributeModel.getNodeTable().getColumn(Modularity.MODULARITY_CLASS);

		List<CoOccurrenceExtractor.Rational> clusters = new ArrayList<CoOccurrenceExtractor.Rational>();
		for (Node n : graphModel.getUndirectedGraphVisible().getNodes()) {
			int cluster = ((Integer) n.getAttributes().getValue(modColumn.getIndex())).intValue();
			while (clusters.size() <= cluster)
				clusters.add(new CoOccurrenceExtractor.Rational(0));
			clusters.get(cluster).numerator++;

			n.getNodeData().setLabel(cluster + "." + clusters.get(cluster).numerator);
		}

		return graphModel;
	}

	/**
	 * First applies a layout algorithm that attracts nodes in the same
	 * community and repels nodes in different communities.
	 * Then applies a layout algorithm to attract all nodes to the center so
	 * that the graph asymptotically approaches a circular shape while still
	 * preserving the distinct communities that emerged in step 1.
	 *
	 * @param graphModel
	 * @param expandIterations
	 * @param compressIterations
	 * @return
	 */
	private static GraphModel layoutNodes(GraphModel graphModel, int expandIterations, int compressIterations) {
		// First separate the clusters
		ForceAtlas2 expandLayout = new ForceAtlas2Builder().buildLayout();
		expandLayout.setGraphModel(graphModel);
		expandLayout.resetPropertiesValues();
		expandLayout.setThreadsCount(Integer.valueOf(Math.max(
			expandLayout.getThreadsCount().intValue(),
			Runtime.getRuntime().availableProcessors()
		)));
		expandLayout.setAdjustSizes(Boolean.TRUE); // "Prevent Overlap"
		expandLayout.initAlgo();
		for (int i = expandIterations - 1; i >= 0 && expandLayout.canAlgo(); --i)
			expandLayout.goAlgo();
		expandLayout.endAlgo();

		// Then compress the clusters into a ball
		FruchtermanReingold compressLayout = new FruchtermanReingoldBuilder().buildLayout();
		compressLayout.setGraphModel(graphModel);
		compressLayout.resetPropertiesValues();
		compressLayout.initAlgo();
		for (int i = compressIterations - 1; i >= 0 && compressLayout.canAlgo(); --i)
			compressLayout.goAlgo();
		compressLayout.endAlgo();

		return graphModel;
	}

	/**
	 * Scales edges by the strength of the relationships. Displays node labels.
	 *
	 * @param graphModel
	 * @return
	 */
	private static GraphModel setPreviewProps(GraphModel graphModel) {
        PreviewController previewController = Lookup.getDefault().lookup(PreviewController.class);

        PreviewModel previewModel = previewController.getModel();
        previewModel.getProperties().putValue(PreviewProperty.EDGE_RESCALE_WEIGHT, Boolean.TRUE);
        previewModel.getProperties().putValue(PreviewProperty.EDGE_THICKNESS, Float.valueOf(2.5f));
        previewModel.getProperties().putValue(PreviewProperty.SHOW_NODE_LABELS, Boolean.TRUE);
        previewModel.getProperties().putValue(PreviewProperty.NODE_LABEL_PROPORTIONAL_SIZE, Boolean.FALSE);
        previewModel.getProperties().putValue(PreviewProperty.NODE_LABEL_FONT,
        		previewModel.getProperties().getFontValue(PreviewProperty.NODE_LABEL_FONT).deriveFont(5f));
        previewController.refreshPreview();

        return graphModel;
	}

	/**
	 * First filters the weakest edges and deletes any isolated nodes.
	 * Then performs the clustering and calculates the weighted degrees.
	 *
	 * @param network
	 * @return
	 */
	public static GraphModel generateClusters(SortedSet<CoOccurrenceExtractor.EntityPair> network) {
		Set<ProperNounProform.NamedEntity> uniqueNodes = new HashSet<ProperNounProform.NamedEntity>();
		for (CoOccurrenceExtractor.EntityPair edge : network) {
			uniqueNodes.add(edge.a);
			uniqueNodes.add(edge.b);
		}

		ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);
		pc.newProject();

		GraphModel graphModel = generateGraph(uniqueNodes, network, pc.getCurrentWorkspace());
		applyFilters(graphModel, MIN_SENTENCES, MIN_PMI, MIN_COMPONENT_SIZE);
		colorByClusters(graphModel, RESOLUTION);
		growByWeightedDegree(graphModel);

		return graphModel;
	}

	/**
	 * Groups nodes into clusters. Prints nodes within clusters by weighted
	 * degree descending.
	 *
	 * @param stream
	 * @param factory
	 * @param graphModel
	 * @param saveLabel
	 * @throws XMLStreamException
	 */
	private static void saveClusters(PrintStream stream, XMLOutputFactory factory, GraphModel graphModel, boolean saveLabel) throws XMLStreamException {
		AttributeModel attributeModel = Lookup.getDefault().lookup(AttributeController.class).getModel();
		AttributeColumn modColumn = attributeModel.getNodeTable().getColumn(Modularity.MODULARITY_CLASS);
		final AttributeColumn degreeColumn = attributeModel.getNodeTable().getColumn(WeightedDegree.WDEGREE);

		Comparator<Node> rankByDegree = Collections.reverseOrder(new Comparator<Node>() {
			@Override
			public int compare(Node a, Node b) {
				return ((Double) a.getAttributes().getValue(degreeColumn.getIndex())).compareTo((Double) b.getAttributes().getValue(degreeColumn.getIndex()));
			}
		});
		List<SortedSet<Node>> clusters = new ArrayList<SortedSet<Node>>();
		for (Node n : graphModel.getUndirectedGraphVisible().getNodes()) {
			int cluster = ((Integer) n.getAttributes().getValue(modColumn.getIndex())).intValue();
			while (clusters.size() <= cluster)
				clusters.add(new TreeSet<Node>(rankByDegree));
			clusters.get(cluster).add(n);
		}

		XMLStreamWriter writer = new IndentingXMLStreamWriter(factory.createXMLStreamWriter(stream));
		writer.writeStartDocument();
		writer.writeStartElement("clusters");
		for (SortedSet<Node> cluster : clusters) {
			writer.writeStartElement("cluster");
			// TODO: pairwise over cluster. if adjacent, sum+=1. score = sum / (n * (n-1) / 2d)
			//graphModel.getUndirectedGraphVisible().isAdjacent(paramNode1, paramNode2);
			for (Node n : cluster) {
				writer.writeStartElement("entity");
				if (saveLabel)
					writer.writeAttribute("label", n.getNodeData().getLabel());
				writer.writeAttribute("degree", n.getAttributes().getValue(degreeColumn.getIndex()).toString());
				writer.writeCharacters(n.getNodeData().getId());
				writer.writeEndElement();
			}
			writer.writeEndElement();
		}
		writer.writeEndElement();
		writer.writeEndDocument();
		writer.close();
		stream.println();
	}

	/**
	 * Lays out the graph to increase visibility of distinct clusters.
	 * Then saves the graph in .gephi, .pdf, and .gexf formats.
	 *
	 * @param graphModel
	 */
	public static void generateVisualization(GraphModel graphModel) {
		ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);

		layoutNodes(graphModel, 16000, 10);
		setPreviewProps(graphModel);

		pc.saveProject(pc.getCurrentProject(), new File("out/clusters.gephi")).run();
		ExportController ec = Lookup.getDefault().lookup(ExportController.class);
		try {
			ec.exportFile(new File("out/clusters.pdf"));
		} catch (IOException ex) {
			ex.printStackTrace();
			return;
		}
		try {
			GraphExporter exporter = (GraphExporter) ec.getExporter("gexf");
			exporter.setExportVisible(true);
			exporter.setWorkspace(pc.getCurrentWorkspace());
			ec.exportFile(new File("out/clusters.gexf"));
		} catch (IOException ex) {
			ex.printStackTrace();
			return;
		}
	}

	public static void main(String[] args) throws XMLStreamException, IOException {
		boolean refresh = false, noGraphs = false;
		File coMentions = null;
		File aliases = null;
		for (int i = 0; i < args.length; i++)
			if (args[i].equals("--force-refresh"))
				refresh = true;
			else if (args[i].equals("--no-graphs"))
				noGraphs = true;
			else if (coMentions == null)
				coMentions = new File(args[i]);
			else if (aliases == null)
				aliases = new File(args[i]);

		PrintStream temp = PrintlnToLog.intercept();
		GraphModel graphModel = generateClusters(CoOccurrenceExtractor.generateNetwork(refresh, coMentions, aliases));
		temp.close();

		XMLOutputFactory factory = XMLOutputFactory.newInstance();
		if (!noGraphs) {
			labelNodes(graphModel);
			saveClusters(System.out, factory, graphModel, true);
			generateVisualization(graphModel);
		} else {
			saveClusters(System.out, factory, graphModel, false);
		}
	}
}
