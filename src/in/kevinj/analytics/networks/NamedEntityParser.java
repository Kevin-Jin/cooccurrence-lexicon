package in.kevinj.analytics.networks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.InputMismatchException;
import java.util.List;
import java.util.Stack;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public class NamedEntityParser {
	private static class Sentence {
		public String[] tokens;

		public Sentence(String[] tokens) {
			this.tokens = tokens;
			for (int i = 0; i < tokens.length; i++) {
				tokens[i] = tokens[i]
					.replaceAll("\\\\\\*", "*")
					.replaceAll("\\\\/", "/")
					.replaceAll("-LRB-", "(")
					.replaceAll("-RRB-", ")")
					.replaceAll("-LSB-", "[")
					.replaceAll("-RSB-", "]")
					.replaceAll("-LCB-", "{")
					.replaceAll("-RCB-", "}")
					.replaceAll("``|''", "\"")
					.replaceAll("`|'", "'")
				;
			}
		}

		public boolean isEmpty() {
			return tokens.length == 1 && tokens[0].trim().isEmpty();
		}

		public void append(Sentence append) {
			String[] temp = new String[tokens.length + append.tokens.length - 1];
			System.arraycopy(tokens, 0, temp, 0, tokens.length);
			temp[tokens.length - 1] += append.tokens[0];
			System.arraycopy(append.tokens, 1, temp, tokens.length, append.tokens.length - 1);
			tokens = temp;
		}

		@Override
		public String toString() {
			return "Sentence[tokens=" + Arrays.toString(tokens) + ']';
		}
	}

	public static abstract class NamedEntity {
		public String type;
		protected int sentence, startToken;
		protected int endToken;

		public void end(Document doc) {
			endToken = doc.text[doc.text.length - 1].tokens.length;

			if (doc.text.length - 1 != sentence) {
				Logger.getLogger(NamedEntityParser.class.getName()).log(Level.FINE, "Named entity spans multiple lines " + doc);

				// hacky: make endToken relative to sentence of startToken
				for (int i = sentence; i < doc.text.length - 1; i++)
					endToken += doc.text[i].tokens.length;
			}
		}

		protected static List<String> getTokens(Document doc, int sentence, int startToken, int endToken) {
			if (sentence >= doc.text.length)
				return null;

			if (endToken <= doc.text[sentence].tokens.length)
				// Entity name is on one sentence.
				return Arrays.asList(doc.text[sentence].tokens).subList(startToken, endToken);

			// Entity name might be in the next sentence.
			while (sentence < doc.text.length && startToken >= doc.text[sentence].tokens.length) {
				startToken -= doc.text[sentence].tokens.length;
				endToken -= doc.text[sentence].tokens.length;
				sentence++;
			}

			// Entity name is split over multiple sentences.
			List<String> tokens = new ArrayList<String>();
			while (sentence < doc.text.length && endToken > 0) {
				tokens.addAll(Arrays.asList(doc.text[sentence].tokens).subList(startToken, Math.min(endToken, doc.text[sentence].tokens.length)));
				endToken -= doc.text[sentence].tokens.length;
				startToken = 0;
				sentence++;
			}

			if (endToken > 0)
				return null;
			else
				return tokens;
		}

		public abstract List<String> getTokens(Document doc);

		public boolean intersects(Document doc, int sentence, int theirStartToken, int theirEndToken) {
			// Entity name might be in the next sentence.
			while (sentence < doc.text.length && theirStartToken >= doc.text[sentence].tokens.length) {
				theirStartToken -= doc.text[sentence].tokens.length;
				theirEndToken -= doc.text[sentence].tokens.length;
				sentence++;
			}
			int theirStartSentence = sentence;
			int theirEndSentence = sentence;
			while (theirEndSentence < doc.text.length && theirEndToken > doc.text[theirEndSentence].tokens.length) {
				theirEndToken -= doc.text[theirEndSentence].tokens.length;
				theirEndSentence++;
			}

			// Normalize.
			sentence = this.sentence;
			int ourStartToken = this.startToken;
			int ourEndToken = this.endToken;
			int ourStartSentence = sentence;
			int ourEndSentence = sentence;
			while (ourStartSentence < theirStartSentence) {
				ourStartToken -= doc.text[ourStartSentence].tokens.length;
				ourStartSentence++;
			}
			while (ourStartSentence > theirStartSentence) {
				ourStartSentence--;
				ourStartToken += doc.text[ourStartSentence].tokens.length;
			}
			while (ourEndSentence < theirEndSentence) {
				ourEndToken -= doc.text[ourEndSentence].tokens.length;
				ourEndSentence++;
			}
			while (ourEndSentence > theirEndSentence) {
				ourEndSentence--;
				ourEndToken += doc.text[ourEndSentence].tokens.length;
			}
			assert ourStartSentence == theirStartSentence && ourEndSentence == theirEndSentence;

			return (theirStartToken >= ourStartToken && theirStartToken < ourEndToken
					|| ourStartToken >= theirStartToken && ourStartToken < theirEndToken);
		}

		@Override
		public String toString() {
			return "NamedEntity[Type=" + type + ",Sentence=" + sentence + ",Start=" + startToken + ",End=" + endToken + ']';
		}
	}

	private static class ExplicitEntity extends NamedEntity {
		public ExplicitEntity(String type, Document doc) {
			this.type = type;
			this.sentence = doc.text.length - 1;
			this.startToken = doc.text[doc.text.length - 1].tokens.length - 1;
		}

		@Override
		public List<String> getTokens(Document doc) {
			return getTokens(doc, sentence, startToken, endToken);
		}
	}

	public static class NamedEntityOccurrence {
		public final int sentence;
		public final String value;

		public NamedEntityOccurrence(int sentence, String value) {
			this.sentence = sentence;
			this.value = value;
		}

		@Override
		public String toString() {
			return "NamedEntityOccurrence[Sentence=" + sentence + ",Value=" + value + ']';
		}
	}

	public static class Document {
		public String name;
		private Sentence[] text;
		public final List<NamedEntity> entities;

		public Document() {
			entities = new ArrayList<NamedEntity>();
		}

		private List<Sentence> parse(String[] sentences) {
			Sentence[] parsed = new Sentence[sentences.length];
			for (int i = 0; i < sentences.length; i++) {
				String[] tokens = sentences[i].split(" ", -1);
				// TODO: is there a pattern with discrepancies in sentence
				// breaking in WSJ.pron?
				parsed[i] = new Sentence(tokens);
			}
			return Arrays.asList(parsed);
		}

		public boolean addText(String[] sentences) {
			List<Sentence> parsedSentences = parse(sentences);

			if (text == null) {
				text = new Sentence[parsedSentences.size()];
				int i = 0;
				for (Sentence sentence : parsedSentences)
					text[i++] = sentence;
			} else {
				text[text.length - 1].append(parsedSentences.get(0));
				Sentence[] temp = new Sentence[text.length - 1 + parsedSentences.size()];
				System.arraycopy(text, 0, temp, 0, text.length);
				int i = text.length;
				for (Sentence sentence : parsedSentences.subList(1, parsedSentences.size()))
					temp[i++] = sentence;
				text = temp;
			}

			return (text.length != 0 && text[text.length - 1].isEmpty());
		}

		public void addEntity(NamedEntity ent) {
			entities.add(ent);
		}

		public void finalizeText() {
			assert (text != null && text.length != 0);

			if (!text[text.length - 1].isEmpty())
				throw new InputMismatchException("Unrecognized format");

			Sentence[] temp = new Sentence[text.length - 1];
			for (int i = 0; i < temp.length; i++) {
				if (text[i].isEmpty())
					throw new InputMismatchException("Unrecognized format");
				temp[i] = text[i];
			}
			text = temp;
		}

		public int getNumberSentences() {
			return text.length;
		}

		public List<NamedEntityOccurrence> getEntityNames() {
			List<NamedEntityOccurrence> allNames = new ArrayList<NamedEntityOccurrence>();

			for (NamedEntity ent : entities)
				allNames.add(new NamedEntityOccurrence(ent.sentence, join(ent.getTokens(this))));

			return allNames;
		}

		@Override
		public String toString() {
			return "Document[Name=" + name + ",Text=" + Arrays.toString(text) /* + ",Entities=" + entities*/ + ']';
		}
	}

	public static String join(List<String> list) {
		if (list.isEmpty())
			return "";

		StringBuilder sb = new StringBuilder();
		sb.append(list.get(0));
		for (String token : list.subList(1, list.size()))
			sb.append(' ').append(token);
		return sb.toString();
	}

	private static String processDocumentName(XMLStreamReader reader) throws XMLStreamException {
		String name = "";

		while (reader.next() != XMLStreamReader.END_ELEMENT || !reader.getLocalName().equals("DOCNO")) {
			if (reader.getEventType() == XMLStreamReader.START_ELEMENT && (reader.getLocalName().equals("ROOT") || reader.getLocalName().equals("DOC") || reader.getLocalName().equals("DOCNO")))
				throw new InputMismatchException("Unrecognized format");

			if (reader.getEventType() == XMLStreamReader.CHARACTERS)
				name += reader.getText();
			else
				throw new InputMismatchException("Unrecognized format");
		}

		return name;
	}

	private static String trimFront(String value) {
		int j;
		for (j = 0; (j < value.length()) && (value.charAt(j) <= ' '); j++);
		return (j > 0) ? value.substring(j) : value;
	}

	private static Document processDocument(XMLStreamReader reader) throws XMLStreamException {
		Document doc = new Document();
		String text = "";
		boolean start = true;
		Stack<NamedEntity> namedEntities = new Stack<NamedEntity>();

		while (reader.next() != XMLStreamReader.END_ELEMENT || !reader.getLocalName().equals("DOC")) {
			if (reader.getEventType() == XMLStreamReader.START_ELEMENT && (reader.getLocalName().equals("ROOT") || reader.getLocalName().equals("DOC")))
				throw new InputMismatchException("Unrecognized format");

			switch (reader.getEventType()) {
				case XMLStreamReader.START_ELEMENT:
					if (reader.getLocalName().equals("DOCNO")) {
						doc.name = processDocumentName(reader).trim();
					} else if (reader.getLocalName().equals("ENAMEX")) {
						String type = reader.getAttributeValue(null, "TYPE");
						if (type == null)
							throw new InputMismatchException("Unrecognized format");

						start = doc.addText(text.split("\r?\n\\s*", -1));
						text = "";
						namedEntities.add(new ExplicitEntity(type, doc));
					} else if (!reader.getLocalName().equals("TIMEX") && !reader.getLocalName().equals("NUMEX")) {
						throw new InputMismatchException("Unrecognized format");
					}
					break;
				case XMLStreamReader.END_ELEMENT:
					if (reader.getLocalName().equals("ENAMEX")) {
						NamedEntity ent = namedEntities.pop();

						if (ent.type.equals("ORGANIZATION:CORPORATION")) {
							start = doc.addText(text.split("\r?\n\\s*", -1));
							text = "";
							ent.end(doc);
							doc.addEntity(ent);
						}
					} else if (!reader.getLocalName().equals("TIMEX") && !reader.getLocalName().equals("NUMEX")) {
						throw new InputMismatchException("Unrecognized format");
					}
					break;
				case XMLStreamReader.CHARACTERS:
					text += reader.getText();
					if (start) {
						text = trimFront(text);
						start = text.isEmpty();
					}
					break;
				default:
					throw new InputMismatchException("Unrecognized format");
			}
		}

		doc.addText(text.split("\r?\n\\s*", -1));
		doc.finalizeText();

		return doc;
	}

	public static List<Document> processNamedEntities(InputStream file) throws IOException, XMLStreamException {
		XMLInputFactory factory = XMLInputFactory.newInstance();
		XMLStreamReader reader = factory.createXMLStreamReader(file);
		try {
			List<Document> documents = new ArrayList<Document>();

			if (reader.getEventType() != XMLStreamReader.START_DOCUMENT)
				throw new InputMismatchException("Unrecognized format");
			if (reader.nextTag() != XMLStreamReader.START_ELEMENT || !reader.getLocalName().equals("ROOT"))
				throw new InputMismatchException("Unrecognized format");

			while (reader.nextTag() != XMLStreamReader.END_ELEMENT || !reader.getLocalName().equals("ROOT")) {
				if (reader.getEventType() == XMLStreamReader.START_ELEMENT && reader.getLocalName().equals("ROOT"))
					throw new InputMismatchException("Unrecognized format");
				if (reader.getEventType() != XMLStreamReader.START_ELEMENT || !reader.getLocalName().equals("DOC"))
					throw new InputMismatchException("Unrecognized format");

				documents.add(processDocument(reader));
			}

			if (reader.next() != XMLStreamReader.END_DOCUMENT)
				throw new InputMismatchException("Unrecognized format");

			return documents;
		} finally {
			reader.close();
		}
	}

	public static List<Document> processNamedEntities(String filename) {
		FileInputStream file = null;
		try {
			file = new FileInputStream(filename);
			return processNamedEntities(file);
		} catch (IOException e) {
			Logger.getLogger(NamedEntityParser.class.getName()).log(Level.WARNING, "Could not load coreferences", e);
			return null;
		} catch (XMLStreamException e) {
			Logger.getLogger(NamedEntityParser.class.getName()).log(Level.WARNING, "Could not load coreferences", e);
			return null;
		} finally {
			if (file != null) {
				try {
					file.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public static void main(String[] args) {
		for (String name : new File("WSJ.clean").list()) {
			if (!name.endsWith(".pron")) {
				for (Document doc : processNamedEntities("WSJ.clean/" + name)) {
					System.out.println(doc);
					//System.out.println(doc.getEntityNames());
				}
			}
		}
	}
}
