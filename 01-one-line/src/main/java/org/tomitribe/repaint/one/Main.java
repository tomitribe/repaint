package org.tomitribe.repaint.one;

public class Main {

    public static void main(final String[] args) throws Exception {
        org.tomitribe.crest.Main.builder()
                .command(OneLine.class)
                .name("one-line")
                .build()
                .run(args);
    }
}
