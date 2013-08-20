package edu.uw.zookeeper.protocol.server;

import java.lang.annotation.*;

import edu.uw.zookeeper.protocol.FourLetterWord;

@Inherited
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface FourLetterCommand {
    FourLetterWord value();
}
