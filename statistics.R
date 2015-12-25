# install.packages('devEMF')

setwd(dirname(parent.frame(2)$ofile))

library(XML)
library('devEMF')
doc <- xmlTreeParse("out/network.xml", getDTD = FALSE)
r <- xmlRoot(doc)
stopifnot (xmlName(r) == "graph")
numbers <- do.call(rbind, lapply(xmlChildren(r), function(d) {
  stopifnot (xmlName(d) == "edge")
  unlist(lapply(xmlAttrs(d)[c("weight", "sentences", "documents")], as.numeric))
}))
rownames(numbers) <- NULL

emf('out/chart1.emf', width = 5, height = 3)
hist(numbers[, "weight"], xlab = "NPMI", main = "All Relationships")
dev.off()
emf('out/chart2.emf', width = 5, height = 3)
hist(numbers[numbers[, "documents"] >= 2 & numbers[, "documents"] <= 30, "weight"], xlab = "Weight", main = "Relationships in at least 2 documents")
dev.off()
emf('out/chart3.emf', width = 5, height = 3)
hist(numbers[numbers[, "sentences"] >= 2, "weight"], xlab = "Weight", main = "Relationships in at least 2 sentences")
dev.off()

rows <- 5
print(rbind(
  data.frame(
    `# Documents Found In` = as.character(1:rows),
    `# Relationships` = unlist(lapply(1:rows, function (i) length(which(numbers[, "documents"] == i))))
    , check.names = FALSE, stringsAsFactors = FALSE
  ),
  list(paste(rows + 1, '+', sep = ""), length(which(numbers[, "documents"] > rows)))
), row.names = FALSE)
rows <- 7
print(rbind(
  data.frame(
    `# Sentences Found In` = as.character(1:rows),
    `# Relationships` = unlist(lapply(1:rows, function (i) length(which(numbers[, "sentences"] == i))))
    , check.names = FALSE, stringsAsFactors = FALSE
  ),
  list(paste(rows + 1, '+', sep = ""), length(which(numbers[, "sentences"] > rows)))
), row.names = FALSE)
#print(paste("Number of relationships that show up in at least 3 documents:", length(which(numbers[, "documents"] > 2))))
#print(paste("Number of relationships that show up in fewer than 3 documents:", length(which(numbers[, "documents"] <= 2))))
#print(paste("Number of relationships that show up in at least 5 sentences:", length(which(numbers[, "sentences"] > 4))))
#print(paste("Number of relationships that show up in fewer than 5 sentences:", length(which(numbers[, "documents"] <= 4))))
