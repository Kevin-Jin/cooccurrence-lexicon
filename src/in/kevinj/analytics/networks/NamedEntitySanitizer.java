package in.kevinj.analytics.networks;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.InputMismatchException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public class NamedEntitySanitizer {
	private enum ProblemType { FRAGMENTS, AND, TAG_MISMATCH, CUT_OFF }

	private static class Problem {
		public final ProblemType type;
		public final Location loc;
		public final String attachment;

		public Problem(ProblemType type, Location loc, String attachment) {
			this.type = type;
			this.loc = loc;
			this.attachment = attachment;
		}

		@Override
		public String toString() {
			return "Problem[Type=" + type + ",Loc=Location[" + loc + "],Attachment=" + attachment + ']';
		}
	}

	public static Problem findProblems(InputStreamReader file) throws IOException {
		XMLInputFactory factory = XMLInputFactory.newInstance();
		XMLStreamReader reader = null;
		try {
			reader = factory.createXMLStreamReader(file);
			if (reader.getEventType() != XMLStreamReader.START_DOCUMENT)
				throw new InputMismatchException("Unrecognized format");

			if (reader.nextTag() == XMLStreamReader.START_ELEMENT && reader.getLocalName().equals("ROOT")) {
				while (reader.next() != XMLStreamReader.END_ELEMENT || !reader.getLocalName().equals("ROOT"))
					if (reader.hasText())
						reader.getText();
				if (reader.next() != XMLStreamReader.END_DOCUMENT)
					throw new InputMismatchException("Unrecognized format");
			} else {
				do; while (reader.next() != XMLStreamReader.END_DOCUMENT);
			}

			return null;
		} catch (XMLStreamException e) {
			int indexOf;
			if (e.getMessage().contains("must end with a '>' delimiter")) {
				return new Problem(ProblemType.CUT_OFF, e.getLocation(), null);
			} else if (e.getMessage().contains("XML document structures must start and end within the same entity.")) {
				return new Problem(ProblemType.CUT_OFF, e.getLocation(), null);
			} else if ((indexOf = e.getMessage().indexOf("must be terminated by the matching end-tag")) != -1) {
				return new Problem(ProblemType.TAG_MISMATCH, e.getLocation(), e.getMessage().substring(indexOf + "must be terminated by the matching end-tag \"".length(), e.getMessage().length() - "\".".length()));
			} else if ((indexOf = e.getMessage().indexOf("must end with the ';' delimiter")) != -1) {
				return new Problem(ProblemType.AND, e.getLocation(), e.getMessage().substring(e.getMessage().indexOf("The reference to entity \"") + "The reference to entity \"".length(), indexOf - "\" ".length()));
			} else if (e.getMessage().contains("\nMessage: The entity name must immediately follow the '&' in the entity reference.")) {
				return new Problem(ProblemType.AND, e.getLocation(), "");
			} else if (e.getMessage().contains("\nMessage: The markup in the document following the root element must be well-formed.")) {
				return new Problem(ProblemType.FRAGMENTS, e.getLocation(), null);
			} else {
				e.printStackTrace();
				return null;
			}
		} finally {
			try {
				if (reader != null)
					reader.close();
			} catch (XMLStreamException e) {
				e.printStackTrace();
			}
		}
	}

	public static Problem findProblems(File file) {
		FileInputStream stream = null;
		InputStreamReader reader = null;
		try {
			return findProblems(reader = new InputStreamReader(stream = new FileInputStream(file), "US-ASCII"));
		} catch (IOException e) {
			Logger.getLogger(NamedEntitySanitizer.class.getName()).log(Level.WARNING, "Could not load coreferences", e);
			return null;
		} finally {
			try {
				if (reader != null)
					reader.close();
				else if (stream != null)
					stream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private static FileChannel[] initChannels(File sourceFile, File destFile) throws IOException {
		if (!destFile.exists())
			destFile.createNewFile();

		FileInputStream input = null;
		FileOutputStream output = null;
		FileChannel source = null;
		FileChannel destination = null;

		try {
			source = (input = new FileInputStream(sourceFile)).getChannel();
			destination = (output = new FileOutputStream(destFile)).getChannel();

			return new FileChannel[] { source, destination };
		} finally {
			if (source == null && input != null)
				input.close();
			if (destination == null && output != null)
				output.close();
		}
	}

	private static void copyFile(FileChannel source, FileChannel destination, long length) throws IOException {
		if (length < 0)
			length = source.size();

		for (long remaining = length - source.position(); remaining > 0;)
			remaining -= destination.transferFrom(source, destination.position(), remaining);
		assert length == source.position();

		destination.position(destination.size());
	}

	private static void copyFile(File sourceFile, File destFile) throws IOException {
		FileChannel[] files = null;

		try {
			files = initChannels(sourceFile, destFile);
			copyFile(files[0], files[1], -1);
		} finally {
			if (files != null) {
				if (files[0] != null)
					files[0].close();
				if (files[1] != null)
					files[1].close();
			}
		}
	}

	private static int getByteOffset(File file, Location loc) throws IOException {
		long remaining = file.length();
		byte[] buffer = new byte[4096];
		int line = 1;
		int offset = loc.getColumnNumber() - 1;

		InputStream stream = new FileInputStream(file);
		try {
			while (remaining > 0 && line < loc.getLineNumber()) {
				int read = stream.read(buffer);
				for (int i = 0; i < read && line < loc.getLineNumber(); i++) {
					if (buffer[i] == '\n')
						line++;
					offset++;
				}
				remaining -= read;
			}
		} finally {
			stream.close();
		}

		// getCharacterOffset() behaves differently with multi-byte encodings
		// for the Reader and Stream source overloads of createXMLStreamReader()
		// but in an inconsistent and meaningless way. Somewhat of a misnomer.
		// We pass the "US-ASCII" encoding to createXMLStreamReader() to rid us
		// of this headache so that we can simply use getCharacterOffset() as a
		// byte offset when seeking in the file.
		if (offset != loc.getCharacterOffset())
			Logger.getLogger(NamedEntitySanitizer.class.getName()).log(Level.FINE, "Wrong character offset in " + file);

		return offset;
	}

	private static String getTrailingWhitespace(FileChannel file, long index) throws IOException {
		String whitespace = "";
		if (index <= 0)
			return whitespace;

		long origPos = file.position();

		char charRead = ' ';
		ByteBuffer readBuf = ByteBuffer.allocate(1);
		for (long nextPos = index - 1; Character.isWhitespace(charRead); nextPos--) {
			file.position(nextPos);

			do
				file.read(readBuf);
			while (readBuf.hasRemaining());
			readBuf.flip();
			charRead = Charset.forName("US-ASCII").decode(readBuf).charAt(0);
			readBuf.clear();

			if (Character.isWhitespace(charRead))
				whitespace = charRead + whitespace;
		}

		file.position(origPos);
		return whitespace;
	}

	private static boolean fixFragments(File overwrite, Problem p) throws IOException {
		File temp = File.createTempFile("WSJ.clean", ".temp");

		FileChannel[] files = null;
		try {
			files = initChannels(overwrite, temp);

			// insert
			ByteBuffer replacement = Charset.forName("US-ASCII").encode(CharBuffer.wrap("<ROOT>"));
			while (replacement.hasRemaining())
				files[1].write(replacement);

			// unchanged
			copyFile(files[0], files[1], -1);

			// insert
			replacement = Charset.forName("US-ASCII").encode(CharBuffer.wrap("</ROOT>" + getTrailingWhitespace(files[0], files[0].size())));
			while (replacement.hasRemaining())
				files[1].write(replacement);
		} finally {
			if (files != null) {
				if (files[0] != null)
					files[0].close();
				if (files[1] != null)
					files[1].close();
			}
		}

		return overwrite.delete() && temp.renameTo(overwrite);
	}

	private static boolean fixAnd(File overwrite, Problem p) throws IOException {
		File temp = File.createTempFile("WSJ.clean", ".temp");

		FileChannel[] files = null;
		try {
			files = initChannels(overwrite, temp);

			// unchanged
			copyFile(files[0], files[1], getByteOffset(overwrite, p.loc) - p.attachment.length());

			// insert
			ByteBuffer replacement = Charset.forName("US-ASCII").encode(CharBuffer.wrap("amp;"));
			while (replacement.hasRemaining())
				files[1].write(replacement);

			// unchanged
			copyFile(files[0], files[1], -1);
		} finally {
			if (files != null) {
				if (files[0] != null)
					files[0].close();
				if (files[1] != null)
					files[1].close();
			}
		}

		return overwrite.delete() && temp.renameTo(overwrite);
	}

	private static boolean fixTagMismatch(File overwrite, Problem p) throws IOException {
		File temp = File.createTempFile("WSJ.clean", ".temp");

		FileChannel[] files = null;
		try {
			files = initChannels(overwrite, temp);

			// unchanged
			copyFile(files[0], files[1], getByteOffset(overwrite, p.loc));

			// insert
			ByteBuffer replacement = Charset.forName("US-ASCII").encode(CharBuffer.wrap(p.attachment.substring(2)));
			while (replacement.hasRemaining())
				files[1].write(replacement);

			// delete
			ByteBuffer original = ByteBuffer.allocate(64);
			boolean found = false;
			do {
				original.clear();
				files[0].read(original);
				original.flip();
				while (!found && original.hasRemaining())
					found = ((char) original.get() == '>');
			} while (!found && files[0].position() < files[0].size());
			if (!found) {
				Logger.getLogger(NamedEntitySanitizer.class.getName()).log(Level.WARNING, "Failed to fix " + overwrite);
				return false;
			}
			while (original.hasRemaining())
				files[1].write(original);

			// unchanged
			copyFile(files[0], files[1], -1);
		} finally {
			if (files != null) {
				if (files[0] != null)
					files[0].close();
				if (files[1] != null)
					files[1].close();
			}
		}

		return overwrite.delete() && temp.renameTo(overwrite);
	}

	private static boolean fixCutOff(File overwrite, Problem p) throws IOException {
		File temp = File.createTempFile("WSJ.clean", ".temp");

		FileChannel[] files = null;
		try {
			files = initChannels(overwrite, temp);

			// unchanged
			int pos = getByteOffset(overwrite, p.loc);
			copyFile(files[0], files[1], pos - getTrailingWhitespace(files[0], pos).length());

			// insert
			ByteBuffer replacement = Charset.forName("US-ASCII").encode(CharBuffer.wrap(">"));
			while (replacement.hasRemaining())
				files[1].write(replacement);

			// unchanged
			copyFile(files[0], files[1], -1);
		} finally {
			if (files != null) {
				if (files[0] != null)
					files[0].close();
				if (files[1] != null)
					files[1].close();
			}
		}

		return overwrite.delete() && temp.renameTo(overwrite);
	}

	public static void correctAllProjects(String source, String destination) {
		try {
			new File(destination).mkdirs();
			Problem p;
			for (String name : new File(source).list()) {
				if (!name.endsWith(".pron")) {
					copyFile(new File(source + '/' + name), new File(destination + '/' + name));
					while ((p = findProblems(new File(destination + '/' + name))) != null) {
						switch (p.type)	{
							case FRAGMENTS:
								fixFragments(new File(destination + '/' + name), p);
								break;
							case AND:
								fixAnd(new File(destination + '/' + name), p);
								break;
							case TAG_MISMATCH:
								fixTagMismatch(new File(destination + '/' + name), p);
								break;
							case CUT_OFF:
								fixCutOff(new File(destination + '/' + name), p);
								break;
						}
					}
				}
				/*if (name.equals("wsj06c.qa.mk.tc")) {
					System.out.println("FIX WSJ0664 new line issue");
				} else if (name.equals("wsj10c.qa.tc.mk")) {
					System.out.println("FIX WSJ1065 new line issue");
				} else if (name.equals("wsj16c.qa.tc.bj")) {
					System.out.println("FIX WSJ1671");
				}*/
			}
		} catch (IOException e) {
			Logger.getLogger(NamedEntitySanitizer.class.getName()).log(Level.WARNING, "Could not sanitize named entity annotations", e);
		}
	}

	public static void main(String[] args) throws IOException {
		correctAllProjects("WSJ", "WSJ.clean");
	}
}
