import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.Tokenizer;
import edu.stanford.nlp.process.TokenizerFactory;
import edu.stanford.nlp.trees.EnglishGrammaticalRelations;
import edu.stanford.nlp.trees.EnglishGrammaticalStructure;
import edu.stanford.nlp.trees.GrammaticalRelation;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TypedDependency;

import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.examples.HtmlToPlainText;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class MyParser {
    // Regular expression for URL matching borrowed from http://stackoverflow.com/questions/15518845/how-to-validate-url-in-java-using-regex
    private final static String urlRegex = "(@)?(href=')?(HREF=')?(HREF=\")?(href=\")?(http://)?[a-zA-Z_0-9\\-]+(\\.\\w[a-zA-Z_0-9\\-]+)+(/[#&\\n\\-=?\\+\\%/\\.\\w]+)?";

    private final static String PCG_MODEL = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";
    private final TokenizerFactory<CoreLabel> tokenizerFactory = PTBTokenizer.factory(new CoreLabelTokenFactory(), "invertible=true");
    private final LexicalizedParser parser = LexicalizedParser.loadModel(PCG_MODEL);

    // List of typed dependencies identified by the TSE2015 paper as useful in extracting
    // meaningful tasks from documentation
    private final static List<GrammaticalRelation> taskTDList =
        new ArrayList<GrammaticalRelation>(Arrays.asList(
            EnglishGrammaticalRelations.DIRECT_OBJECT,
            EnglishGrammaticalRelations.PREPOSITIONAL_MODIFIER,
            EnglishGrammaticalRelations.AGENT,
            EnglishGrammaticalRelations.NOMINAL_PASSIVE_SUBJECT,
            EnglishGrammaticalRelations.RELATIVE_CLAUSE_MODIFIER,
            EnglishGrammaticalRelations.NEGATION_MODIFIER,
            EnglishGrammaticalRelations.PHRASAL_VERB_PARTICLE,
            EnglishGrammaticalRelations.NOUN_COMPOUND_MODIFIER,
            EnglishGrammaticalRelations.ADJECTIVAL_MODIFIER
        ));

    // Four primary typed dependencies to analyze for extracting tasks in this project
    private final static GrammaticalRelation[] mainTDArray = new GrammaticalRelation[] {
        EnglishGrammaticalRelations.DIRECT_OBJECT,
        EnglishGrammaticalRelations.PREPOSITIONAL_MODIFIER,
        EnglishGrammaticalRelations.NOMINAL_PASSIVE_SUBJECT,
        EnglishGrammaticalRelations.RELATIVE_CLAUSE_MODIFIER
    };
    private final static List<GrammaticalRelation> mainTDList =
        new ArrayList<GrammaticalRelation>(Arrays.asList(mainTDArray));

    // Corresponding String abbreviations of the above four typed dependencies
    private final static String[] shortNamesArray = new String[] {
        EnglishGrammaticalRelations.DIRECT_OBJECT.getShortName(),
        EnglishGrammaticalRelations.PREPOSITIONAL_MODIFIER.getShortName(),
        EnglishGrammaticalRelations.NOMINAL_PASSIVE_SUBJECT.getShortName(),
        EnglishGrammaticalRelations.RELATIVE_CLAUSE_MODIFIER.getShortName()
    };
    private final static Set<String> shortNames =
        new HashSet<String>(Arrays.asList(shortNamesArray));


    public static void main(String[] args) {
        try {
            extractTasks("http://jowanza.com/post/89790077794/analyzing-kendrick-lamar-lyrics-with-javascript");
            // extractTasks("https://docs.djangoproject.com/en/1.9/intro/tutorial01/");
        }
        catch (IOException e) {
            System.out.println(e);
        }
    }

    public static void extractTasks(String url) throws IOException {
        Document doc = Jsoup.connect(url).get();

        /* Uncomment below segment for reading and parsing a local HTML file. */
        // File localFile = new File(url);
        // Document doc = Jsoup.parse(localFile, "UTF-8");
        // Element docBody = doc.body();

        // Remove code blocks from document body.
        Elements preTags = docBody.getElementsByTag("pre");
        for (Element e : preTags) {
            e.empty();
        }
        Elements codeTags = docBody.getElementsByTag("code");
        for (Element e : codeTags) {
            e.empty();
        }
        Elements blockquoteTags = docBody.getElementsByTag("blockquote");
        for (Element e : blockquoteTags) {
            e.empty();
        }

        // TODO(austinhle): Also dig for iframes to find nested <body> tags.

        HtmlToPlainText htpt = new HtmlToPlainText();
        String content = htpt.getPlainText(docBody).replaceAll(urlRegex, "URL");

        String[] sentences = content.split(Pattern.quote("."));

        MyParser parser = new MyParser();
        File file = new File("output.tsv");
        BufferedWriter bw = new BufferedWriter(new FileWriter(file));

        for (String sentence : sentences) {
            if (sentence.trim().length() == 0) {
                continue;
            }

            // Clean up sentences for output.
            sentence = sentence.replaceAll("\n", " ").replaceAll("\t", " ");
            Tree tree = parser.parse(sentence);

            EnglishGrammaticalStructure egs = new EnglishGrammaticalStructure(tree);
            for (TypedDependency td : egs.typedDependenciesCollapsed()) {
              GrammaticalRelation reln = td.reln();
              if (mainTDList.contains(reln)) {   // Found a typed dependency we are interested in.
                  // Write to TSV file in tab-separated format.
                  bw.write(reln.getShortName());
                  bw.write("\t");
                  bw.write(td.gov().word());
                  bw.write("\t");
                  bw.write(td.dep().word());
                  bw.write("\t");
                  bw.write(sentence);
                  bw.write("\n");
              }
            }
        }

        bw.close();

        /* == Other websites to experiment task extraction on. ==
         * Django Getting Started docs
         * http://jekyllrb.com/
         * http://www.hnwatcher.com/r/1165889/-Using-Node-js-to-Analyze-Kendrick-Lamar-Lyrics
         * https://github.com/nlp-compromise/nlp_compromise
         */
    }

    public Tree parse(String str) {
        List<CoreLabel> tokens = tokenize(str);
        Tree tree = parser.apply(tokens);
        return tree;
    }

    private List<CoreLabel> tokenize(String str) {
        Tokenizer<CoreLabel> tokenizer = tokenizerFactory.getTokenizer(new StringReader(str));
        return tokenizer.tokenize();
    }
}
