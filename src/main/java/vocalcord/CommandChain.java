package vocalcord;

import net.dv8tion.jda.api.entities.User;

import java.util.ArrayList;
import java.util.HashMap;

public class CommandChain {

    private PhraseVector termMatrix;
    private double minThreshold = 0.5;
    private ArrayList<Command> commands;
    private Task fallback;

    public static class Builder {
        private ArrayList<Command> commands = new ArrayList<>();
        private CommandChain cc = new CommandChain();

        public Builder add(String phrase, Task job) {
            commands.add(new Command(phrase, job));
            return this;
        }

        public Builder withFallback(Task task) {
            cc.fallback = task;
            return this;
        }

        public Builder withMinThreshold(float minThreshold) {
            cc.minThreshold = minThreshold;
            return this;
        }

        public CommandChain build() {
            if(commands.size() == 0) throw new RuntimeException("Must provide at least one command");

            PhraseVector vec = new PhraseVector(new HashMap<>());

            for(Command c : commands) {
                vec = vec.merge(c.vector);
            }

            cc.termMatrix = vec;
            cc.commands = commands;
            return cc;
        }
    }

    /*
     * Internal code
     */

    private CommandChain() {}

    public interface Task {
        void run(User user, String transcript);
    }

    static class Command {
        PhraseVector vector;
        Task job;

        public Command(String phrase, Task job) {
            this.vector = new PhraseVector(phrase);
            this.job = job;
        }
    }

    private static class PhraseVector {
        private final HashMap<String, Integer> words;

        static final String[] STOP_WORDS =
                {"i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you", "your", "yours", "yourself", "yourselves", "he", "him", "his", "himself", "she", "her", "hers", "herself",
                        "it", "its", "itself", "they", "them", "their", "theirs", "themselves", "what", "which", "who", "whom", "this", "that", "these", "those", "am", "is", "are", "was", "were",
                        "be", "been", "being", "have", "has", "had", "having", "do", "does", "did", "doing", "a", "an", "the", "and", "but", "if", "or", "because", "as", "until", "while", "of", "at",
                        "by", "for", "with", "about", "against", "between", "into", "through", "during", "before", "after", "above", "below", "to", "from", "up", "down", "in", "out", "on", "off", "over",
                        "under", "again", "further", "then", "once", "here", "there", "when", "where", "why", "how", "all", "any", "both", "each", "few", "more", "most", "other", "some", "such", "no",
                        "nor", "not", "only", "own", "same", "so", "than", "too", "very", "s", "t", "can", "will", "just", "don", "should", "now"};

        PhraseVector(String phrase) {
            words = new HashMap<>();
            phrase = scrub(phrase);

            String[] tokens = phrase.split("\\s+");
            for(String word : tokens) {
                int count = words.getOrDefault(word, 0);
                words.put(word, count + 1);
            }
        }

        private PhraseVector(HashMap<String, Integer> words) {
            this.words = words;
        }

        private static String scrub(String phrase) {
            phrase = phrase.replaceAll("[^a-zA-z0-9 -]", "").trim();

            for(String s : STOP_WORDS) {
                phrase = phrase.replaceAll(s, "");
            }

            return phrase;
        }

        // Does not preserve frequencies
        PhraseVector merge(PhraseVector vec) {
            HashMap<String, Integer> merged = new HashMap<>();
            merged.putAll(words);
            merged.putAll(vec.words);
            return new PhraseVector(merged);
        }

        int[] asVector(HashMap<String, Integer> termMatrix) {
            int[] vector = new int[termMatrix.size()];

            int index = 0;
            for(String key : termMatrix.keySet()) {
                vector[index] = words.getOrDefault(key, 0);
                index++;
            }

            return vector;
        }

        double cosine(HashMap<String, Integer> termMatrix, PhraseVector vec) {
            int[] vec1 = asVector(termMatrix);
            int[] vec2 = vec.asVector(termMatrix);

            if(vec1.length != vec2.length) {
                throw new RuntimeException("Vector lengths must be the same.");
            }

            int innerProduct = 0;
            double vec1Length = 0;
            double vec2Length = 0;

            for(int i = 0; i < vec1.length; i++) {
                innerProduct += (vec1[i] * vec2[i]);

                vec1Length += (vec1[i] * vec1[i]);
                vec2Length += (vec2[i] * vec2[i]);
            }

            return (double)innerProduct / (Math.sqrt(vec1Length) * Math.sqrt(vec2Length));
        }
    }

    static class CommandCandidate {
        double similarity;
        Command cmd;
        String transcript;

        private CommandCandidate(double similarity, Command cmd, String transcript) {
            this.similarity = similarity;
            this.cmd = cmd;
            this.transcript = transcript;
        }
    }

    CommandCandidate test(String transcript) {
        double maxSimilarity = -1;
        int maxIndex = -1;

        PhraseVector transcriptVector = new PhraseVector(transcript);

        for(int i = 0; i < commands.size(); i++) {
            double similarity = transcriptVector.cosine(termMatrix.words, commands.get(i).vector);
            if(similarity > maxSimilarity) {
                maxSimilarity = similarity;
                maxIndex = i;
            }
        }

        if(maxIndex == -1) {
            return null;
        } else {
            return new CommandCandidate(maxSimilarity, commands.get(maxIndex), transcript);
        }
    }

    public void execute(User user, CommandCandidate candidate) {
        if(candidate == null || candidate.similarity < minThreshold && fallback != null) {
            fallback.run(user, candidate == null ? "" : candidate.transcript);
        } else {
            System.out.println("Command executed with certainty: "+candidate.similarity);
            candidate.cmd.job.run(user, candidate.transcript);
        }
    }

}