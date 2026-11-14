/** The supervision board: state, reducer, decider and the single board writer. */
@ApplicationModule(allowedDependencies = {"snmp", "alarms"})
package io.github.selvitopu.atmsup.supervisor.board;

import org.springframework.modulith.ApplicationModule;
