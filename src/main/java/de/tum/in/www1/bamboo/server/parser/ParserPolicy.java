package de.tum.in.www1.bamboo.server.parser;

import de.tum.in.www1.bamboo.server.parser.domain.Tool;

public class ParserPolicy {

    private ReportParser parser;

    public ParserPolicy(ReportParser parser) {
        this.parser = parser;
    }

    public void configure(String tool) throws ParserException {
        if (Tool.SPOTBUGS.name().equalsIgnoreCase(tool)) {
            parser.setParserStrategy(new SpotbugsParser());
        } else {
            throw new ParserException("Report parsing for tool" + tool + "is not supported");
        }
    }
}
