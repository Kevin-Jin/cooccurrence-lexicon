package in.kevinj.analytics.networks;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.InputMismatchException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CoreferenceParser {
	public static class Range {
		public final int sentence;
		public final int startToken, endToken;

		public Range(String strVal) {
			try {
				int front, back;
				if (strVal.charAt(0) != 'S')
					throw new InputMismatchException("Unrecognized format");

				front = 1;
				back = strVal.indexOf(':', front);
				if (back == -1)
					throw new InputMismatchException("Unrecognized format");
				sentence = Integer.parseInt(strVal.substring(front, back)) - 1;

				front = back + 1;
				back = strVal.indexOf('-', front);
				if (back == -1)
					throw new InputMismatchException("Unrecognized format");
				startToken = Integer.parseInt(strVal.substring(front, back)) - 1;

				front = back + 1;
				endToken = Integer.parseInt(strVal.substring(front));
			} catch (NumberFormatException e) {
				throw new InputMismatchException("Unrecognized format");
			}
		}

		@Override
		public String toString() {
			return "Range[sentence=" + sentence + ",startToken=" + startToken + ",endToken=" + endToken + ']';
		}
	}

	public static class Entry {
		public final Range range;
		public final String val;

		public Entry(String range, String val) {
			this.range = new Range(range);
			this.val = val;
		}

		@Override
		public String toString() {
			return "Entry[range=" + range + ",val=" + val + ']';
		}
	}

	public static class Coreference {
		public static final Coreference EMPTY = new Coreference(null, null);

		public final List<Entry> antecedents;
		public final List<Entry> pronouns;

		private Coreference(List<Entry> antecedents, List<Entry> pronouns) {
			this.antecedents = antecedents;
			this.pronouns = pronouns;
		}

		public Coreference() {
			this(new ArrayList<Entry>(), new ArrayList<Entry>());
		}

		@Override
		public String toString() {
			return "Coreference[antecedents=" + antecedents + ",pronouns=" + pronouns + ']';
		}
	}

	private static class Document {
		public String name;
		public final List<Coreference> coreferences;
	
		public Document() {
			this.name = "";
			this.coreferences = new ArrayList<Coreference>();
		}

		@Override
		public String toString() {
			return "Document[name=" + name + ",coreferences=" + coreferences + ']';
		}
	}

	private static void processEntry(Coreference coref, String key, String range, String val) {
		if (coref == null || coref == Coreference.EMPTY)
			throw new InputMismatchException("Unrecognized format");

		if (key.equals("Antecedent")) {
			coref.antecedents.add(new Entry(range, val));
		} else if (key.equals("Pronoun")) {
			coref.pronouns.add(new Entry(range, val));
		} else {
			throw new InputMismatchException("Unrecognized format");
		}
	}

	public static Map<String, List<Coreference>> processCoreferences(BufferedReader file) throws IOException {
		String line;
		int nest = 0;

		Map<String, List<Coreference>> corpus = new HashMap<String, List<Coreference>>();
		Document doc = null;
		Coreference coref = null;
		String key = null;
		String range = null;
		String val = null;
		while ((line = file.readLine()) != null) {
			for (int i = 0; i < line.length(); i++) {
				switch (line.charAt(i)) {
					case '(':
						nest++;
						if (coref != null) {
							if (nest != 2)
								throw new InputMismatchException("Unrecognized format");

							key = "";
							coref = new Coreference();
							doc.coreferences.add(coref);
						} else {
							if (nest != 1)
								throw new InputMismatchException("Unrecognized format");

							if (doc != null) corpus.put(doc.name, doc.coreferences);
							doc = new Document();
							coref = Coreference.EMPTY;
							//corpus.add(doc);
						}
						break;
					case ')':
						nest--;
						if (nest == 0) {
							if ((doc.name = doc.name.trim()).isEmpty())
								throw new InputMismatchException("Unrecognized format");

							if (doc != null) corpus.put(doc.name, doc.coreferences);
							doc = null;
							coref = null;
						}
						if (nest == 1) {
							if (!key.trim().isEmpty()) {
								if (val == null || (val = val.trim()).isEmpty())
									throw new InputMismatchException("Unrecognized format");
	
								processEntry(coref, key, range, val);
							}

							key = null;
							range = null;
							val = null;
						}
						break;
					case '>':
						if (key == null)
							throw new InputMismatchException("Unrecognized format");

						if (range == null) {
							if (key.charAt(key.length() - 1) == '-') {
								key = key.substring(0, key.length() - 1).trim();
								range = "";
							} else {
								key += line.charAt(i);
							}
						} else if (val == null) {
							if (range.charAt(range.length() - 1) == '-') {
								range = range.substring(0, range.length() - 1).trim();
								val = "";
							} else {
								range += line.charAt(i);
							}
						} else {
							val += line.charAt(i);
						}
						break;
					case '\n':
						break;
					default:
						if (val != null)
							val += line.charAt(i);
						else if (range != null)
							range += line.charAt(i);
						else if (key != null)
							key += line.charAt(i);
						else if (doc != null && doc.coreferences.isEmpty())
							doc.name += line.charAt(i);
						else if (!Character.isWhitespace(line.charAt(i)))
							throw new InputMismatchException("Unrecognized format");
						break;
				}
			}
			if (val != null && !(val = val.trim()).isEmpty()) {
				processEntry(coref, key, range, val);

				key = "";
				range = null;
				val = null;
			}
		}
		if (doc != null) corpus.put(doc.name, doc.coreferences);

		return corpus;
	}

	public static Map<String, List<Coreference>> processCoreferences(String filename) {
		BufferedReader file = null;
		try {
			file = new BufferedReader(new FileReader(filename));
			return processCoreferences(file);
		} catch (IOException e) {
			Logger.getLogger(CoreferenceParser.class.getName()).log(Level.WARNING, "Could not load coreferences", e);
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

	public static void main(String[] args) throws IOException {
		System.out.println(processCoreferences("WSJ/WSJ.pron"));
	}
}
