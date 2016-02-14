import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.Tokenizer;
import edu.stanford.nlp.process.TokenizerFactory;
import edu.stanford.nlp.trees.EnglishGrammaticalStructure;
import edu.stanford.nlp.trees.Tree;

import java.io.StringReader;
import java.util.List;
import java.util.HashMap;

public class MyParser {
    // Final variables
    private final static String PCG_MODEL = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";
    private final TokenizerFactory<CoreLabel> tokenizerFactory = PTBTokenizer.factory(new CoreLabelTokenFactory(), "invertible=true");
    private final LexicalizedParser parser = LexicalizedParser.loadModel(PCG_MODEL);

    public static void main(String[] args) {
        // Some sample string.
        String str = "Never run Bower with sudo. Bower is a user command; there is no need to execute it with superuser permissions.";

        extractTasks(str);
    }

    public static void extractTasks(String str) {
        HashMap<String, Integer> posCount = new HashMap<String, Integer>();

        MyParser parser = new MyParser();
        Tree tree = parser.parse(str);

        System.out.println("=== Parsing input structure to assign parts-of-speech. ===");
        List<Tree> leaves = tree.getLeaves();
        // Print words and Pos Tags
        for (Tree leaf : leaves) {
            Tree parent = leaf.parent(tree);

            String word = leaf.label().value();
            String pos = parent.label().value();

            // Update posCount HashMap to keep final totals updated
            if (posCount.containsKey(pos)) {
                posCount.put(pos, posCount.get(pos) + 1);
            } else {
                posCount.put(pos, 1);
            }

            System.out.print(word + "-" + pos + " ");
        }
        System.out.println("");

        System.out.println("Final Part-of-Speech Counts: ");
        for (String key : posCount.keySet()) {
            System.out.printf("%s: %d\n", key, posCount.get(key));
        }

        System.out.println("=== Parsing tree structure to obtain grammatical dependencies. ===");

        EnglishGrammaticalStructure egs = new EnglishGrammaticalStructure(tree);
        System.out.println("EnglishGrammaticalStructure:");
        System.out.println(egs.typedDependenciesCollapsed());

        // TODO(austinhle): Filter through typed dependencies and extract tasks by inspecting nodes.
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
