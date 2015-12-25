package in.kevinj.analytics.networks;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A heuristic black box that determines whether two company names refer to the
 * same organization entity.
 *
 * By far the hardest part of the entire project. I hacked together a bunch of
 * features that work together but I'm not sure whether there is a better way.
 *
 * @author Kevin Jin
 */
public class ProperNounProform {
	public static class NamedEntity implements Comparable<NamedEntity> {
		public final Set<String> aliases;
		public String key;
		private int maxFrontDeletes, maxBackDeletes;

		public NamedEntity(String initial) {
			aliases = new LinkedHashSet<String>();
			key = initial;
			aliases.add(initial);
		}

		public void add(String alias, String newKey) {
			key = newKey;
			aliases.add(alias);
		}

		@Override
		public String toString() {
			return '[' + key + ']';
		}

		@Override
		public int compareTo(NamedEntity other) {
			return this.key.compareTo(other.key);
		}
	}

	private static String removePrefix(String name) {
		Matcher m = Pattern.compile("^("
			// Begins with "the"
			+ "[Tt][Hh][Ee]"
		+ " )+(.*)$").matcher(name);
		if (m.matches())
			name = m.group(2);
		return name;
	}

	// TODO: get a dictionary similar to
	// https://en.wikipedia.org/wiki/Types_of_business_entity
	private static String removeSuffix(String name) {
		Matcher m = Pattern.compile("^(.*?)("
			+ " (, )?("
				// Ends with "and Company", or variations
				+ "((& ?|and )?[Cc]o(mpany| ?\\.)?)"
				// Ends with a period e.g. Corp.
				+ "|[^ ]{1,5}( ?\\.)"
				// Ends with all caps token between 2 to 4 letters long e.g. PLC
				+ "|[A-Z]{2,5}" // RLLLP is 5 characters
//				+ "|'s" // NE tagger mistakenly includes possessive sometimes
			+ ")"
		+ ")+$").matcher(name);
		if (m.matches())
			name = m.group(1);
		return name;
	}

	private static String removePunct(String name, boolean periods) {
		name = name
			// Goldman, Sachs & Co. -> Goldman Sachs & Co.
			.replaceAll(" ,$", "").replaceAll("^, ", "").replaceAll("( |),\\1", "$1")
			// Time-Warner -> Time Warner
			.replaceAll(" -$", "").replaceAll("^- ", "").replaceAll("( |)-\\1", "$1")
			// Dunkin' -> Dunkin
			.replaceAll(" '$", "").replaceAll("^' ", "").replaceAll("( |)'\\1", "$1")
			// Guber/Peters -> Guber Peters
			.replaceAll(" /$", "").replaceAll("^/ ", "").replaceAll("( |)/\\1", "$1")
			// Goldman, Sachs & Co. -> Goldman, Sachs and Co.
			.replaceAll(" &$", " and").replaceAll("^& ", "and ").replaceAll("( |)&\\1", " and ")
		;
		if (periods)
			name = name.replaceAll(" \\.$", "").replaceAll("^\\. ", "").replaceAll("( |)\\.\\1", "$1");
		return name;
	}

	/**
	 * Not an article, coordinate conjunction, preposition, or punctuation.
	 *
	 * @param token
	 * @return
	 */
	private static boolean isImportantToken(String token) {
		return token.matches("^[A-Z].*");
	}

	private static int nextCapital(String str, int start) {
		for (int i = start; i < str.length(); i++)
			if (Character.isUpperCase(str.charAt(i)))
				return i;
		return -1;
	}

	/**
	 * E.g. "Aluminum Company of America" == "Alcoa",
	 * "American Express" == "Amex", "Consolidated Rail" == "Conrail",
	 * "Atlantic and Pacific" == "A&P", etc.
	 *
	 * One named entity must be an abbreviated form of the other. "Amex Co."
	 * cannot be equal to "American Express". Using the vocabulary of edit
	 * distance, name1 can consist entirely of deletions from, or entirely of
	 * insertions to, name2, but cannot contain any substitutions or a mix.
	 * All periods, commas, and spaces in the abbreviation are ignored.
	 *
	 * @param name1
	 * @param name2
	 * @param firstIsAbbrev
	 * @return if negative, neither named entity is an abbreviation of the
	 * other. Otherwise, returns the number of tokens that remain in the full
	 * name not found in the abbreviation.
	 */
	private static int isPortmanteauOrAcronym(String name1, String name2, boolean firstIsAbbrev) {
		// Comma tokens and commas inside tokens are insignificant.
		// Also replace & with and.
		name1 = removePunct(name1, false);
		name2 = removePunct(name2, false);

		String abbrev;
		String[] fullName;
		if (firstIsAbbrev) {
			abbrev = name1;
			fullName = name2.split(" ");
		} else {
			abbrev = name2;
			fullName = name1.split(" ");
		}

		Stack<int[]> backtrack = new Stack<int[]>();
		int i, j, k, m;
		for (i = 0, j = 0, k = 0, m = 0; i < abbrev.length(); ) {
			boolean hasNextCharacter = k < fullName[j].length();
			int nextCapital = nextCapital(fullName[j], k + 1);
			int thisOrNextCapital = hasNextCharacter && Character.isUpperCase(fullName[j].charAt(k)) ? k : nextCapital;

			// Can try to stay on the current word
			boolean option1 = hasNextCharacter && (Character.toLowerCase(abbrev.charAt(i)) == Character.toLowerCase(fullName[j].charAt(k)));
			// Can try to move onto the next word
			boolean option2 = j + 1 < fullName.length && (m != 0 || !isImportantToken(fullName[j]));
			// Period can also jump to next capital letter in fullName[j]
			boolean option3 = m != 0 && (abbrev.charAt(i) == ' ' || abbrev.charAt(i) == '.') && nextCapital != -1;
			// Capital letters can also jump to next capital letter in fullName[j]
			boolean option4 = m != 0 && Character.isUpperCase(abbrev.charAt(i)) && nextCapital != -1;

			if (Character.isUpperCase(abbrev.charAt(i))) {
				if (option1 && Character.isLowerCase(fullName[j].charAt(k)))
					// "TWA"/="Time Warner". "MCI"/="McDermott International".
					// If next letter in abbrev is capitalized and next letter
					// in fullName[j] is not, must skip.
					option1 = false;
				if (option2 && thisOrNextCapital != -1 && fullName[j].charAt(thisOrNextCapital) != abbrev.charAt(i))
					// Have to go to next capital letter in fullName[j] first
					// before skipping to the next word.
					option2 = false;
				if (option4 && m != 0 && hasNextCharacter && Character.isUpperCase(fullName[j].charAt(k)))
					// abbrev must not skip capital letter in fullName[j]
					option4 = false;
			}

			if (option1) {
				// Keep track of all alternatives
				if (option2) {
					// TODO: do the unimportant token loop outside of option1 branch
					int advance = 1;
					do {
						if (Character.toLowerCase(abbrev.charAt(i)) == Character.toLowerCase(fullName[j + advance].charAt(0)))
							backtrack.push(new int[] { i, j + advance, 0 });
						advance++;
					} while (j + advance < fullName.length && !isImportantToken(fullName[j + advance - 1]));
				}
				if (option3) {
					if (i + 1 < abbrev.length() && Character.toUpperCase(abbrev.charAt(i + 1)) == fullName[j].charAt(nextCapital))
						backtrack.push(new int[] { i + 1, j, nextCapital });
				}
				if (option4) {
					if (Character.toUpperCase(abbrev.charAt(i)) == fullName[j].charAt(nextCapital))
						backtrack.push(new int[] { i, j, nextCapital });
				}

				// Stay on the current token
				i++;
				k++;
				m++;
			} else if (option2) {
				// TODO: do unimportant token loop backtrack
				if (option3) {
					if (i + 1 < abbrev.length() && Character.toUpperCase(abbrev.charAt(i + 1)) == fullName[j].charAt(nextCapital))
						backtrack.push(new int[] { i + 1, j, nextCapital });
				}
				if (option4) {
					if (Character.toUpperCase(abbrev.charAt(i)) == fullName[j].charAt(nextCapital))
						backtrack.push(new int[] { i, j, nextCapital });
				}

				// Move onto the next token
				if (abbrev.charAt(i) == ' ' || abbrev.charAt(i) == '.')
					i++;
				j++;
				k = 0;
				m = 0;
			} else if (option3) {
				if (option4) {
					if (Character.toUpperCase(abbrev.charAt(i)) == fullName[j].charAt(nextCapital))
						backtrack.push(new int[] { i, j, nextCapital });
				}

				i++;
				k = nextCapital;
				m = 0;
			} else if (option4) {
				k = nextCapital;
				m = 0;
			} else if (abbrev.charAt(i) == ' ' || abbrev.charAt(i) == '.') {
				// Try ignoring the character
				i++;
			} else if (!backtrack.isEmpty()) {
				// Try an alternation
				int[] temp = backtrack.pop();
				i = temp[0];
				j = temp[1];
				k = temp[2];
			} else {
				// Abbreviation candidate includes a char not in the full name
				return -1;
			}
		}

		if (k != 0)
			j++;
		return fullName.length - j;
	}

	@SuppressWarnings("unused")
	private static int isPortmanteauOrAcronym(String name1, String name2) {
		boolean firstIsAbbrev = name1.length() < name2.length();
		if (isPortmanteauOrAcronym(name1, name2, firstIsAbbrev) < 0)
			return 0;
		else
			if (firstIsAbbrev)
				return 2;
			else
				return 1;
	}

	private static String join(String[] array, String delimit, int from, int to) {
		to = Math.min(to, array.length);
		if (from >= to)
			return "";

		StringBuilder sb = new StringBuilder();
		sb.append(array[from]);
		for (int i = from + 1; i < to; i++)
			sb.append(delimit).append(array[i]);
		return sb.toString();
	}

	// FIXME: performance issues in here?
	/**
	 * Performs deletion on tokens before attempting isPortmanteauOrAcronym().
	 *
	 * @param name1
	 * @param name2
	 * @return
	 */
	private static int isSubsetPortmanteauOrAcronym(String name1, String name2) {
		boolean firstIsAbbrev = name1.length() < name2.length();
		String clean1 = name1;
		String clean2 = name2;

		// First try without shortening the names
		int back = isPortmanteauOrAcronym(clean1, clean2, clean1.length() < clean2.length());
		if (back == 0)
			return firstIsAbbrev ? 2 : 1;

		if (firstIsAbbrev) {
			// name1 is shorter. Try shortening name2 in a couple of ways
			clean2 = removePrefix(removeSuffix(name2));
			back = isPortmanteauOrAcronym(clean1, clean2, clean1.length() < clean2.length());
			if (back == 0)
				return firstIsAbbrev ? 2 : 1;
			/*clean2 = removePrefix(name2);
			back = isPortmanteauOrAcronym(clean1, clean2, clean1.length() < clean2.length());
			if (back == 0)
				return firstIsAbbrev ? 2 : 1;
			clean2 = removeSuffix(name2);
			back = isPortmanteauOrAcronym(clean1, clean2, clean1.length() < clean2.length());
			if (back == 0)
				return firstIsAbbrev ? 2 : 1;*/
		} else {
			// name2 is shorter. Try shortening name1 in a couple of ways
			clean1 = removePrefix(removeSuffix(name1));
			back = isPortmanteauOrAcronym(clean1, clean2, clean1.length() < clean2.length());
			if (back == 0)
				return firstIsAbbrev ? 2 : 1;
			/*clean1 = removePrefix(name1);
			back = isPortmanteauOrAcronym(clean1, clean2, clean1.length() < clean2.length());
			if (back == 0)
				return firstIsAbbrev ? 2 : 1;
			clean1 = removeSuffix(name1);
			back = isPortmanteauOrAcronym(clean1, clean2, clean1.length() < clean2.length());
			if (back == 0)
				return firstIsAbbrev ? 2 : 1;*/
		}

		// Try shortening both names in a couple of ways
		clean1 = removePrefix(removeSuffix(name1));
		clean2 = removePrefix(removeSuffix(name2));
		back = isPortmanteauOrAcronym(clean1, clean2, clean1.length() < clean2.length());
		if (back == 0)
			return firstIsAbbrev ? 2 : 1;
		/*clean1 = removePrefix(name1);
		clean2 = removePrefix(name2);
		back = isPortmanteauOrAcronym(clean1, clean2, clean1.length() < clean2.length());
		if (back == 0)
			return firstIsAbbrev ? 2 : 1;
		clean1 = removeSuffix(name1);
		clean2 = removeSuffix(name2);
		back = isPortmanteauOrAcronym(clean1, clean2, clean1.length() < clean2.length());
		if (back == 0)
			return firstIsAbbrev ? 2 : 1;*/

		return 0;
	}

	private static boolean isEmpty(String[] array) {
		return array.length == 0 || array.length == 1 && "".equals(array[0]);
	}

	// TODO: priority system if multiple named entities can fit. Exact match is
	// preferred, followed by fewest number of token deletions, followed by
	// acronyms, followed by portmanteaus.
	/**
	 * Returns the longer of the two names. If the shorter name becomes the key
	 * "J.P. Morgan -> Morgan" can be matched with "Morgan -> Morgan Stanley"!
	 * Keeping the longer name prevents "J.P. Morgan -> Morgan Stanley"
	 * because the edit distance includes both an insertion and a deletion,
	 * and only all insertions or all deletions are allowed when determining
	 * equivalency of two named entities.
	 *
	 * @param name1
	 * @param name2
	 * @return
	 */
	public static boolean addAlias(NamedEntity ent, String name2) {
		String name1 = ent.key;

		String[] clean1 = removePunct(removePrefix(removeSuffix(name1)), true).split(" ");
		String[] clean2 = removePunct(removePrefix(removeSuffix(name2)), true).split(" ");
		if (isEmpty(clean1) && isEmpty(clean2))
			// If both are empty.
			return name1.equalsIgnoreCase(name2);
		else if (isEmpty(clean1) || isEmpty(clean2))
			// If one is empty.
			return false;

		String[] toShorten;
		String abbrev;
		if (clean1.length > clean2.length) {
			toShorten = clean1;
			abbrev = join(clean2, " ", 0, clean2.length);
		} else {
			toShorten = clean2;
			abbrev = join(clean1, " ", 0, clean1.length);
		}
		// Initially only allow one additional token at end or start of name.
		// If we find an alias that is longer, then that alias becomes the
		// primary representation but we still allow future names to delete up
		// to the same extent as in the initial name, by tolerating additional
		// tokens to delete.
		// If we find an alias that is shorter, then we allow future names to
		// tolerate an additional token to delete.
		// Basically, this routine tolerates deleting an additional token off
		// the shortest alias of the named entity.
		for (int front = 0; front <= ent.maxFrontDeletes + 1; front++) {
			// TODO: if !isImportantToken(deleted word in front), then
			// continue and allow one more word to be deleted from the front.
			for (int back = 0; back <= ent.maxBackDeletes + 1; back++) {
				// TODO: if !isImportantToken(deleted word in back), then
				// continue and allow one more word to be deleted from the back.
				String shorter = join(toShorten, " ", front, toShorten.length - back);
				// If we come down to a single token and it's unimportant, like
				// "and" or ".", then this can't be an alias.
				if (front + 1 >= toShorten.length - back && !isImportantToken(shorter))
					continue;

				if (shorter.equalsIgnoreCase(abbrev)) {
					if (name2.length() > name1.length()) {
						ent.key = name2;
						ent.maxFrontDeletes += front;
						ent.maxBackDeletes += back;
					} else {
						ent.maxFrontDeletes = Math.max(ent.maxFrontDeletes, front);
						ent.maxBackDeletes = Math.max(ent.maxBackDeletes, back);
					}
					ent.aliases.add(name2);
					return true;
				}
			}
		}

		// Acronyms and portmanteaus have lots of false positives, so never
		// delete any tokens except for prefixes and suffixes in the test.
		switch (isSubsetPortmanteauOrAcronym(name1, name2)) {
			case 0:
			default:
				return false;
			case 1:
				ent.aliases.add(name2);
				return true;
			case 2:
				ent.key = name2;
				ent.aliases.add(name2);
				return true;
		}
	}

	public static boolean merge(NamedEntity a, NamedEntity b) {
		// FIXME: temporarily loosen maxFrontDeletes and maxBackDeletes in 'a'
		// using maxFrontDeletes and maxBackDeletes from 'b'
		if (addAlias(a, b.key)) {
			a.aliases.addAll(b.aliases);
			// TODO: maxFrontDeletes and maxBackDeletes stuff
			return true;
		}
		return false;
	}

	public static void testChain(String... chain) {
		if (chain.length < 2)
			return;

		NamedEntity test = new NamedEntity(chain[0]);
		for (int i = 1; i < chain.length; i++)
			if (addAlias(test, chain[i]))
				System.out.print('(' + test.key + ' ' + test.maxFrontDeletes + ' ' + test.maxBackDeletes + ") ");
			else
				System.out.print("NOMATCH ");
		System.out.println();
	}

	public static void main(String[] args) {
		testChain("Lloyd 's", "Lloyd 's Bank");
		testChain("Warners", "Warner-Lambert Co .", "Warner-Lambert 's");

		testChain("The Ringing World", "TRW", "TRW Inc.");

		// False positives
		// FIXME: minimum length requirement for front and back delete
		testChain("News Corp.", "Detroit News"); // "News"
		testChain("Northeast Utilities", "Utilities", "Northeast");
		testChain("Time Warner", "Warner Bros."); // "Warner"
		testChain("Times-Stock Exchange", "Exchange");
		testChain("Toronto Stock Exchange", "Stock Exchange");
		// FIXME: priority system
		// [Fiat S.p.A.-controlled, Fiat S.p . A] split with [First Atlanta, Fiat S.p . A., Fiat]
		// [Resolution Trust Corp .] split with [RTC, Resolution Trust Corp.]
		// [Prudential Insurance Co. of America, Prudential Insurance Co. of America .] split with [Prudential, Prudential ,, Prudential Insurance]
		// [Mips Computer Systems Inc.] split with [MIPs ,, Mips]

		// False negatives
		// FIXME: ???
		testChain("Deseret Bancorp.", "Deseret", "Deseret Bank");
		// FIXME: "and" should not be counted in maxBackDeletes, maxFrontDeletes
		testChain("Deloitte", "Deloitte-Touche", "Deloitte and Touche");
		// FIXME: try both alternatives: removing " 's" and removing " '"
		// [Lloyd 's, Lloyd 's Bank]
		// [Warner-Lambert Co., Warner-Lambert Co ., Warner-Lambert] split with [Warners, Warner-Lambert 's]
		// [Guber/Peters Entertainment Co., Guber/Peters, Guber-Peters Entertainment Co., Guber-Peters Entertainment, Guber-Peters] split with [Guber Peters Entertainment Co., Guber Peters, Guber Peters Entertainment Co .]
		// [Wendy 's International Inc ., Wendy 's International Inc.] split with [Wendy 's]
		// [Christies International PLC, Christie 's International PLC] split with [Christie 's, Christies]
		// [Sotheby 's Holdings Inc.] split with [Sotheby 's, Sotheby 's Inc., Sotheby]
		// [Fireman 's] split with [Fireman 's Fund Corp., Fireman 's Fund]
		// [CENTRUST SAVINGS BANK] split with [CenTrust Bank, CenTrust, CenTrust 's]
		testChain("Christie 's", "Christie 's International PLC");
		testChain("Young 's Market Co.", "Young 's");

		testChain(
			"A&P Company",
			"The Atlantic and Pacific Company",
			"Atlantic and",
			"The Great Atlantic and Pacific Tea Company",
			"The Atlantic and Pacific Company",
			"A&P",
			"Great Atlantic & Pacific",
			"A&P",
			"Atlantic and"
		);

		// Other weird stuff: Warner, MCI Communications, MGM, Mitsui, NWA, LIN, Rothschild, Oppenheimer, [AT&T] Bell Lab[oratorie]s, Banco Popular, SciMed, Bay View Federal, Bumiputra Malaysia, Societe Commerciale, NatWest, HEI, First Options, PacifiCare, Sunbelt, CityFed, [Sony-]Columbia [Pictures], NBC-owned, National Broadcasting Co
	}
}
