package tech.kayys.wayang.rag;

/** One retrieved passage. {@code text} is what to give the model (the parent window when expansion is on); {@code chunk} says where it came from, for citations. */
public record Hit(Chunk chunk, double score, String text, String method) {
}
