# cooccurrence-lexicon

You must have the BBN named entity annotations for the Penn Treebank, and its
parallel pronoun resolution file, in the WSJ folder under the root directory.
This means that relative to the folder that contains clusters.sh and
clusters.bat, the files WSJ/WSJ.pron, WSJ/wsj00a.qa.bj.lm, etc. should exist.

If you do not have access to the BBN NE corpus, I can provide it on request as
long as it is strictly for an educational purpose or otherwise satisfies Fair
Use.

Executing clusters.sh or clusters.bat will go through the full process.
It will generate the files clusters.gephi (a Gephi project file), clusters.gexf,
clusters.pdf (visualization, nodes are labeled with IDs), and clusters.xml (the
key that maps node IDs in the visualization to entity names) in the out folder.

If comentions.xml and aliases.xml already exist in the out folder, they will be
used. Otherwise, all of the intermediary files will be generated from scratch
using the BBN corpus and its pronoun resolution file.
NOTE: if you are regenerating the intermediary files, the alias resolution
program will take a long time to complete. Maybe even up to half an hour! The
graph layout algorithms may take a minute or two to complete too.
NOTE: I had file permission problems when Dropbox was running. If you have a
file sync service running, you may have to temporarily disable it while the
corpus cleaner program is running.

I generated charts and tables using the included statistics.R script, which
requires that the out/network.xml file be generated. This file can be generated
with comentions.sh or comentions.bat.
