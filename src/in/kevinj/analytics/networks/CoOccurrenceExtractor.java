package in.kevinj.analytics.networks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.InputMismatchException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import com.sun.xml.internal.txw2.output.IndentingXMLStreamWriter;

/**
 * Uses a BBN named entity annotated corpus and coreference annotations to
 * determine which pairs of companies are most related to each other by
 * quantifying the persistence of same-sentence co-mentions.
 *
 * Can be used to determine which companies are the strongest competitors to one
 * another, which companies have a symbiotic relationship, or which companies
 * supply or serve each other.
 *
 * This kind of pattern is not something humans naturally look for when they
 * read Wall Street Journal news articles or when journalists publish them.
 * Hopefully this project will shed additional insight into the strengths of
 * relationships between different companies.
 *
 * @author Kevin Jin
 */
public class CoOccurrenceExtractor {
	private static class CoreferencedEntity extends NamedEntityParser.NamedEntity {
		private List<String> antecedent;

		private CoreferencedEntity(NamedEntityParser.NamedEntity complement, NamedEntityParser.Document doc, CoreferenceParser.Range range) {
			this.type = complement.type;
			this.sentence = range.sentence;
			this.startToken = range.startToken;
			this.endToken = range.endToken;
			this.antecedent = Collections.unmodifiableList(complement.getTokens(doc));
		}

		@Override
		public List<String> getTokens(NamedEntityParser.Document doc) {
			return antecedent;
		}

		public static List<CoreferencedEntity> make(CoreferenceParser.Coreference coref, NamedEntityParser.Document doc) {
			List<CoreferencedEntity> allAntecedentMatches = new ArrayList<CoreferencedEntity>();

			for (CoreferenceParser.Entry realName : coref.antecedents) {
				CoreferenceParser.Range range = realName.range;
				List<String> tokens = getTokens(doc, range.sentence, range.startToken, range.endToken);
				if (tokens == null || !realName.val.equals(NamedEntityParser.join(tokens))) {
					Logger.getLogger(NamedEntityParser.class.getName()).log(Level.FINE, "Coreference file mismatch " + doc + " " + coref);
					continue;
				}

				List<CoreferenceParser.Entry> validAliases = new ArrayList<CoreferenceParser.Entry>();
				for (CoreferenceParser.Entry alias : coref.pronouns) {
					CoreferenceParser.Range aliasRange = alias.range;
					tokens = getTokens(doc, aliasRange.sentence, aliasRange.startToken, aliasRange.endToken);
					if (tokens == null || !alias.val.equals(NamedEntityParser.join(tokens))) {
						Logger.getLogger(NamedEntityParser.class.getName()).log(Level.FINE, "Coreference file mismatch " + doc + " " + coref);
						continue;
					}

					validAliases.add(alias);
				}
				for (NamedEntityParser.NamedEntity ent : doc.entities)
					if (ent.intersects(doc, range.sentence, range.startToken, range.endToken))
						for (CoreferenceParser.Entry alias : validAliases)
							allAntecedentMatches.add(new CoreferencedEntity(ent, doc, alias.range));
			}
			return allAntecedentMatches;
		}
	}

	private static class Document {
		public final int totalSentences;
		public final List<Set<ProperNounProform.NamedEntity>> interestingSentences;

		public Document(int totalSentences, List<Set<ProperNounProform.NamedEntity>> interestingSentences) {
			this.totalSentences = totalSentences;
			this.interestingSentences = interestingSentences;
		}

		@Override
		public String toString() {
			return "Document[Total=" + totalSentences + ",Interesting=" + interestingSentences + ']';
		}
	}

	private static class PairwiseIterator<T extends Comparable<T>> implements Iterator<List<T>> {
		private final Object[] array;
		private int i, j;

		public PairwiseIterator(Collection<T> elements) {
			array = elements.toArray();
			i = 0;
			j = 1;
		}

		@Override
		public boolean hasNext() {
			return !(i >= array.length || i == array.length - 1 && j >= array.length);
		}

		@SuppressWarnings("unchecked")
		@Override
		public List<T> next() {
			if (!hasNext())
				throw new NoSuchElementException();

			List<T> result;
			int cmp = ((T) array[i]).compareTo((T) array[j]);
			if (cmp > 0)
				result = Arrays.asList((T) array[j], (T) array[i]);
			else if (cmp < 0)
				result = Arrays.asList((T) array[i], (T) array[j]);
			else
				throw new IllegalStateException("Different entities with same key");

			if (j + 1 < array.length) {
				j++;
			} else {
				i++;
				j = i + 1;
			}

			return result;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}

		public static <T extends Comparable<T>> Iterable<List<T>> iterable(final Collection<T> elements) {
			return new Iterable<List<T>>() {
				public Iterator<List<T>> iterator() {
					return new PairwiseIterator<T>(elements);
				}
			};
		}
	}

	@SuppressWarnings("serial")
	public static class Rational extends Number {
		public int numerator, denominator;

		public Rational(int numerator, int denominator) {
			this.numerator = numerator;
			this.denominator = denominator;
		}

		public Rational(int initialVal) {
			this(initialVal, 1);
		}

		@Override
		public int intValue() {
			return numerator / denominator;
		}

		@Override
		public long longValue() {
			return intValue();
		}

		@Override
		public float floatValue() {
			return (float) numerator / denominator;
		}

		@Override
		public double doubleValue() {
			return (double) numerator / denominator;
		}

		@Override
		public String toString() {
			return Double.toString(doubleValue());
		}
	}

	public static class EntityPair implements Comparable<EntityPair> {
		private static final NumberFormat FMT = new DecimalFormat("0.00");

		public final ProperNounProform.NamedEntity a, b;
		public final double relationship;
		public final int sentences, documents;

		public EntityPair(ProperNounProform.NamedEntity a, ProperNounProform.NamedEntity b, double relationship, int sentences, int documents) {
			this.a = a;
			this.b = b;
			this.relationship = relationship;
			this.sentences = sentences;
			this.documents = documents;
		}

		@Override
		public int compareTo(EntityPair other) {
			int delta = (int) (this.relationship - other.relationship);
			if (delta == 0) {
				if (this.relationship > other.relationship)
					delta = 1;
				else if (this.relationship < other.relationship)
					delta = -1;
			}

			if (delta == 0)
				delta = this.sentences - other.sentences;
			if (delta == 0)
				delta = this.documents - other.documents;

			if (delta == 0)
				delta = this.a.compareTo(other.a);
			if (delta == 0)
				delta = this.b.compareTo(other.b);

			return delta;
		}

		@Override
		public String toString() {
			return "NamedEntityPair[Strength=" + FMT.format(relationship) + ",a=" + a + ",b=" + b + ']';
		}
	}

	@SuppressWarnings("unchecked")
	private static <E> List<Set<E>> newArrayOfSets(int length) {
		return Arrays.asList((Set<E>[]) Array.newInstance(Set.class, length));
	}

	/**
	 * Filters by sentences that mention at least two named entities.
	 *
	 * @param dirty
	 * @return
	 */
	private static <T> List<Set<T>> interestingSentences(List<Set<T>> dirty) {
		List<Set<T>> filtered = new ArrayList<Set<T>>();
		for (Set<T> element : dirty)
			if (element != null && element.size() > 1)
				filtered.add(element);
		return filtered;
	}

	private static void loadClean(Map<String, Document> documents, Map<String, ProperNounProform.NamedEntity> allEnts) {
		Map<String, List<CoreferenceParser.Coreference>> corefs = CoreferenceParser.processCoreferences("WSJ/WSJ.pron");
		Map<String, ProperNounProform.NamedEntity> reverseMapping = new HashMap<String, ProperNounProform.NamedEntity>();
		List<ProperNounProform.NamedEntity> aliases = new ArrayList<ProperNounProform.NamedEntity>();
		NamedEntitySanitizer.correctAllProjects("WSJ", "WSJ.clean");

		for (String name : new File("WSJ.clean").list()) {
			if (!name.endsWith(".pron")) {
				for (NamedEntityParser.Document doc : NamedEntityParser.processNamedEntities("WSJ.clean/" + name)) {
					List<CoreferenceParser.Coreference> refs = corefs.get(doc.name);
					if (refs == null)
						continue;

					for (CoreferenceParser.Coreference coref : refs)
						for (CoreferencedEntity ent : CoreferencedEntity.make(coref, doc))
							doc.addEntity(ent);

					List<Set<ProperNounProform.NamedEntity>> sentenceCoMention = newArrayOfSets(doc.getNumberSentences());
//					List<ProperNounProform.NamedEntity> docEnts = new ArrayList<ProperNounProform.NamedEntity>();
					for (NamedEntityParser.NamedEntityOccurrence occurrence : doc.getEntityNames()) {
						Set<ProperNounProform.NamedEntity> forSentence = sentenceCoMention.get(occurrence.sentence);
						if (forSentence == null) {
							forSentence = new LinkedHashSet<ProperNounProform.NamedEntity>();
							sentenceCoMention.set(occurrence.sentence, forSentence);
						}

						// Find previous instances of this named entity
						ProperNounProform.NamedEntity unique = null;
						// See if this alias was used verbatim in the past
						unique = reverseMapping.get(occurrence.value.toLowerCase());
						if (unique == null) {
							// Otherwise, compare against all previous keys.
							// FIXME: only look at named entities in this
							// document. Then merge in the end.
							for (ProperNounProform.NamedEntity prevFound : aliases) {
								if (ProperNounProform.addAlias(prevFound, occurrence.value)) {
									// Previous instance of this entity found!
									unique = prevFound;
									reverseMapping.put(occurrence.value.toLowerCase(), unique);
									break;
								}
							}
						}

						if (unique == null) {
							// Named entity was not found in any preceding doc
							unique = new ProperNounProform.NamedEntity(occurrence.value);
							reverseMapping.put(occurrence.value.toLowerCase(), unique);
//							docEnts.add(unique);
							aliases.add(unique);
						}

						// Associate named entity to this sentence
						forSentence.add(unique);
					}

					List<Set<ProperNounProform.NamedEntity>> interesting = interestingSentences(sentenceCoMention);
					if (!interesting.isEmpty())
						documents.put(doc.name, new Document(doc.getNumberSentences(), interesting));
					System.err.println(doc.name);
				}
			}
		}

		// This is the only way to preserve insertion order. If we did remove()
		// and put() whenever the key changed, insertion order into the
		// LinkedHashMap gets muddled up.
		for (ProperNounProform.NamedEntity entity : aliases)
			allEnts.put(entity.key, entity);
	}

	private static void saveCoMentions(PrintStream stream, XMLOutputFactory factory, Map<String, Document> documents) throws XMLStreamException {
		XMLStreamWriter writer = new IndentingXMLStreamWriter(factory.createXMLStreamWriter(stream));
		writer.writeStartDocument();
		writer.writeStartElement("corpus");
		for (Map.Entry<String, Document> entry : documents.entrySet()) {
			List<Set<ProperNounProform.NamedEntity>> sentences = entry.getValue().interestingSentences;

			writer.writeStartElement("document");
			writer.writeAttribute("name", entry.getKey());
			writer.writeAttribute("sentences", Integer.toString(entry.getValue().totalSentences));

			for (Set<ProperNounProform.NamedEntity> sentence : sentences) {
				writer.writeStartElement("sentence");
				for (ProperNounProform.NamedEntity entity : sentence) {
					writer.writeStartElement("entity");
					writer.writeCharacters(entity.key);
					writer.writeEndElement();
				}
				writer.writeEndElement();
			}

			writer.writeEndElement();
		}
		writer.writeEndElement();
		writer.writeEndDocument();
		writer.close();
		stream.println();
	}

	private static void saveAliases(PrintStream stream, XMLOutputFactory factory, Map<String, ProperNounProform.NamedEntity> allEnts) throws XMLStreamException {
		XMLStreamWriter writer = new IndentingXMLStreamWriter(factory.createXMLStreamWriter(stream));
		writer.writeStartDocument();
		writer.writeStartElement("aliases");
		for (ProperNounProform.NamedEntity entity : allEnts.values()) {
			writer.writeStartElement("entity");
			writer.writeAttribute("key", entity.key);

			for (String alias : entity.aliases) {
				writer.writeStartElement("alias");
				writer.writeCharacters(alias);
				writer.writeEndElement();
			}

			writer.writeEndElement();
		}
		writer.writeEndElement();
		writer.writeEndDocument();
		writer.close();
		stream.println();
	}

	private static Set<ProperNounProform.NamedEntity> processSentence(XMLStreamReader reader, Map<String, ProperNounProform.NamedEntity> allEnts) throws XMLStreamException {
		Set<ProperNounProform.NamedEntity> forSentence = new LinkedHashSet<ProperNounProform.NamedEntity>();

		String entity = "";
		String tag;
		while (reader.next() != XMLStreamReader.END_ELEMENT || !(tag = reader.getLocalName()).equals("sentence")) {
			if (reader.getEventType() == XMLStreamReader.START_ELEMENT && ((tag = reader.getLocalName()).equals("corpus") || tag.equals("document") || tag.equals("sentence")))
				throw new InputMismatchException("Unrecognized format");

			switch (reader.getEventType()) {
				case XMLStreamReader.START_ELEMENT:
					if ((tag = reader.getLocalName()).equals("entity")) {
						entity = "";
					} else {
						throw new InputMismatchException("Unrecognized format");
					}
					break;
				case XMLStreamReader.END_ELEMENT:
					if ((tag = reader.getLocalName()).equals("entity")) {
						ProperNounProform.NamedEntity aliases = allEnts.get(entity);
						if (aliases == null)
							throw new InputMismatchException("Missing aliases");
						forSentence.add(aliases);
					} else {
						throw new InputMismatchException("Unrecognized format");
					}
					break;
				case XMLStreamReader.CHARACTERS:
					entity += reader.getText();
					break;
				default:
					throw new InputMismatchException("Unrecognized format");
			}
		}

		return forSentence;
	}

	private static Document processDocument(XMLStreamReader reader, Map<String, ProperNounProform.NamedEntity> allEnts) throws XMLStreamException {
		int sentences = Integer.parseInt(reader.getAttributeValue("", "sentences"));
		List<Set<ProperNounProform.NamedEntity>> sentenceCoMention = new ArrayList<Set<ProperNounProform.NamedEntity>>();
		String tag;
		while (reader.nextTag() != XMLStreamReader.END_ELEMENT || !(tag = reader.getLocalName()).equals("document")) {
			if (reader.getEventType() == XMLStreamReader.START_ELEMENT && ((tag = reader.getLocalName()).equals("corpus") || tag.equals("document")))
				throw new InputMismatchException("Unrecognized format");
			if (reader.getEventType() != XMLStreamReader.START_ELEMENT || !(tag = reader.getLocalName()).equals("sentence"))
				throw new InputMismatchException("Unrecognized format");

			sentenceCoMention.add(processSentence(reader, allEnts));
		}
		return new Document(sentences, sentenceCoMention);
	}

	private static void loadCoMention(InputStream stream, XMLInputFactory factory, Map<String, Document> documents, Map<String, ProperNounProform.NamedEntity> allEnts) throws XMLStreamException {
		XMLStreamReader reader = factory.createXMLStreamReader(stream);

		if (reader.getEventType() != XMLStreamReader.START_DOCUMENT)
			throw new InputMismatchException("Unrecognized format");
		if (reader.nextTag() != XMLStreamReader.START_ELEMENT || !reader.getLocalName().equals("corpus"))
			throw new InputMismatchException("Unrecognized format");

		@SuppressWarnings("unused")
		String tag;
		while (reader.nextTag() != XMLStreamReader.END_ELEMENT || !(tag = reader.getLocalName()).equals("corpus")) {
			if (reader.getEventType() == XMLStreamReader.START_ELEMENT && (tag = reader.getLocalName()).equals("corpus"))
				throw new InputMismatchException("Unrecognized format");
			if (reader.getEventType() != XMLStreamReader.START_ELEMENT || !(tag = reader.getLocalName()).equals("document"))
				throw new InputMismatchException("Unrecognized format");

			String name = reader.getAttributeValue("", "name");
			if (documents.put(name, processDocument(reader, allEnts)) != null)
				throw new InputMismatchException("Unrecognized format");
		}

		if (reader.next() != XMLStreamReader.END_DOCUMENT)
			throw new InputMismatchException("Unrecognized format");		
	}

	private static ProperNounProform.NamedEntity processEntity(XMLStreamReader reader, String key) throws XMLStreamException {
		ProperNounProform.NamedEntity unique = new ProperNounProform.NamedEntity(key);

		String alias = "";
		String tag;
		while (reader.next() != XMLStreamReader.END_ELEMENT || !(tag = reader.getLocalName()).equals("entity")) {
			if (reader.getEventType() == XMLStreamReader.START_ELEMENT && ((tag = reader.getLocalName()).equals("aliases") || tag.equals("entity")))
				throw new InputMismatchException("Unrecognized format");

			switch (reader.getEventType()) {
				case XMLStreamReader.START_ELEMENT:
					if ((tag = reader.getLocalName()).equals("alias")) {
						alias = "";
					} else {
						throw new InputMismatchException("Unrecognized format");
					}
					break;
				case XMLStreamReader.END_ELEMENT:
					if ((tag = reader.getLocalName()).equals("alias")) {
						unique.aliases.add(alias);
					} else {
						throw new InputMismatchException("Unrecognized format");
					}
					break;
				case XMLStreamReader.CHARACTERS:
					alias += reader.getText();
					break;
				default:
					throw new InputMismatchException("Unrecognized format");
			}
		}

		return unique;
	}

	private static void loadAliases(InputStream stream, XMLInputFactory factory, Map<String, ProperNounProform.NamedEntity> allEnts) throws XMLStreamException {
		XMLStreamReader reader = factory.createXMLStreamReader(stream);

		if (reader.getEventType() != XMLStreamReader.START_DOCUMENT)
			throw new InputMismatchException("Unrecognized format");
		if (reader.nextTag() != XMLStreamReader.START_ELEMENT || !reader.getLocalName().equals("aliases"))
			throw new InputMismatchException("Unrecognized format");

		@SuppressWarnings("unused")
		String tag;
		while (reader.nextTag() != XMLStreamReader.END_ELEMENT || !(tag = reader.getLocalName()).equals("aliases")) {
			if (reader.getEventType() == XMLStreamReader.START_ELEMENT && (tag = reader.getLocalName()).equals("aliases"))
				throw new InputMismatchException("Unrecognized format");
			if (reader.getEventType() != XMLStreamReader.START_ELEMENT || !(tag = reader.getLocalName()).equals("entity"))
				throw new InputMismatchException("Unrecognized format");

			String key = reader.getAttributeValue("", "key");
			if (allEnts.put(key, processEntity(reader, key)) != null)
				throw new InputMismatchException("Unrecognized format");
		}

		if (reader.next() != XMLStreamReader.END_DOCUMENT)
			throw new InputMismatchException("Unrecognized format");		
	}

	// Should we care about how important each co-occurrence is to the document?
	// E.g. if Apple-Microsoft is the only relationship mentioned in an article,
	// is that a very strong signal that the two companies are very related?
	private static void processTfdf(Map<String, Document> documents) {
		for (Map.Entry<String, Document> document : documents.entrySet()) {
			List<Set<ProperNounProform.NamedEntity>> sentences = document.getValue().interestingSentences;
			Map<List<ProperNounProform.NamedEntity>, Rational> pairInstances = new LinkedHashMap<List<ProperNounProform.NamedEntity>, Rational>();

			for (Set<ProperNounProform.NamedEntity> sentence : sentences) {
				for (List<ProperNounProform.NamedEntity> pair : PairwiseIterator.iterable(sentence)) {
					Rational c = pairInstances.get(pair);
					if (c == null) {
						c = new Rational(1, sentences.size());
						pairInstances.put(pair, c);
					} else {
						c.numerator++;
					}
				}
			}
		}
	}

	private static SortedSet<EntityPair> processMutualInformation(Map<String, Document> documents) {
		Map<ProperNounProform.NamedEntity, Rational> entFrequencies = new LinkedHashMap<ProperNounProform.NamedEntity, Rational>();
		Map<List<ProperNounProform.NamedEntity>, Rational> pairFrequencies = new LinkedHashMap<List<ProperNounProform.NamedEntity>, Rational>();
		Map<List<ProperNounProform.NamedEntity>, Rational> pairDocFrequencies = new LinkedHashMap<List<ProperNounProform.NamedEntity>, Rational>();
		int corpusSize = 0;

		for (Map.Entry<String, Document> document : documents.entrySet()) {
			Set<List<ProperNounProform.NamedEntity>> pairsInDoc = new HashSet<List<ProperNounProform.NamedEntity>>();
			for (Set<ProperNounProform.NamedEntity> sentence : document.getValue().interestingSentences) {
				for (ProperNounProform.NamedEntity entity : sentence) {
					Rational c = entFrequencies.get(entity);
					if (c == null) {
						c = new Rational(1);
						entFrequencies.put(entity, c);
					} else {
						c.numerator++;
					}
				}
				corpusSize += sentence.size();
				for (List<ProperNounProform.NamedEntity> pair : PairwiseIterator.iterable(sentence)) {
					Rational c = pairFrequencies.get(pair);
					if (c == null) {
						c = new Rational(1);
						pairFrequencies.put(pair, c);
					} else {
						c.numerator++;
					}
					pairsInDoc.add(pair);
				}
			}
			for (List<ProperNounProform.NamedEntity> pair : pairsInDoc) {
				Rational c = pairDocFrequencies.get(pair);
				if (c == null) {
					c = new Rational(1);
					pairDocFrequencies.put(pair, c);
				} else {
					c.numerator++;
				}
			}
		}

		SortedSet<EntityPair> sortedPairs = new TreeSet<EntityPair>();
		for (Map.Entry<List<ProperNounProform.NamedEntity>, Rational> pair : pairFrequencies.entrySet()) {
			// P(X == pair[0] && Y == pair[1]) == freq(pair[0], pair[1]) / N
			double probJoint = pair.getValue().doubleValue() / corpusSize;
			// P(X == pair[0]) == freq(pair[0]) / N
			double probX = entFrequencies.get(pair.getKey().get(0)).doubleValue() / corpusSize;
			// P(X == pair[1]) == freq(pair[1]) / N
			double probY = entFrequencies.get(pair.getKey().get(1)).doubleValue() / corpusSize;
			// Pointwise mutual information
			double pmi = Math.log(probJoint / (probX * probY)) / Math.log(2);

			// "The PMI of perfectly correlated words is higher when the
			// combination is less frequent." Let's fix that.
			double normalized = pmi / (-Math.log(probJoint) / Math.log(2));
			sortedPairs.add(new EntityPair(
				pair.getKey().get(0),
				pair.getKey().get(1),
				normalized,
				pair.getValue().intValue(),
				pairDocFrequencies.get(pair.getKey()).intValue()
			));
		}
		return sortedPairs;
	}

	public static SortedSet<EntityPair> generateNetwork(boolean refresh, File coMentions, File aliases) throws XMLStreamException, IOException {
		Map<String, ProperNounProform.NamedEntity> allEnts = new LinkedHashMap<String, ProperNounProform.NamedEntity>();
		Map<String, Document> documents = new LinkedHashMap<String, Document>();

		refresh = refresh || !coMentions.exists() || !aliases.exists();
		if (refresh) {
			loadClean(documents, allEnts);
			XMLOutputFactory factory = XMLOutputFactory.newInstance();

			PrintStream stream = System.out;
			if (coMentions != null)
				stream = new PrintStream(new FileOutputStream(coMentions));
			try {
				saveCoMentions(stream, factory, documents);
			} finally {
				if (coMentions != null)
					stream.close();
			}
	
			stream = System.out;
			if (aliases != null)
				stream = new PrintStream(new FileOutputStream(aliases));
			try {
				saveAliases(stream, factory, allEnts);
			} finally {
				if (aliases != null)
					stream.close();
			}
		} else {
			XMLInputFactory factory = XMLInputFactory.newInstance();

			InputStream stream = new FileInputStream(aliases);
			try {
				loadAliases(stream, factory, allEnts);
			} finally {
				stream.close();
			}

			stream = new FileInputStream(coMentions);
			try {
				loadCoMention(stream, factory, documents, allEnts);
			} finally {
				stream.close();
			}
		}

		processTfdf(documents);
		return processMutualInformation(documents);
	}

	private static void saveNetwork(PrintStream stream, XMLOutputFactory factory, Set<EntityPair> relationships) throws XMLStreamException {
		XMLStreamWriter writer = new IndentingXMLStreamWriter(factory.createXMLStreamWriter(stream));
		writer.writeStartDocument();
		writer.writeStartElement("graph");
		for (EntityPair pair : relationships) {
			writer.writeStartElement("edge");
			writer.writeAttribute("weight", Double.toString(pair.relationship));
			writer.writeAttribute("sentences", Integer.toString(pair.sentences));
			writer.writeAttribute("documents", Integer.toString(pair.documents));

			writer.writeStartElement("node");
			writer.writeCharacters(pair.a.key);
			writer.writeEndElement();

			writer.writeStartElement("node");
			writer.writeCharacters(pair.b.key);
			writer.writeEndElement();

			writer.writeEndElement();
		}
		writer.writeEndElement();
		writer.writeEndDocument();
		writer.close();
		stream.println();
	}

	public static void main(String[] args) throws XMLStreamException, IOException {
		boolean refresh = false;
		File coMentions = null;
		File aliases = null;
		for (int i = 0; i < args.length; i++)
			if (args[i].equals("--force-refresh"))
				refresh = true;
			else if (coMentions == null)
				coMentions = new File(args[i]);
			else if (aliases == null)
				aliases = new File(args[i]);

		XMLOutputFactory factory = XMLOutputFactory.newInstance();
		saveNetwork(System.out, factory, generateNetwork(refresh, coMentions, aliases));
	}
}
