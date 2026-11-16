/** Commands a supervisor sends to a unit by SNMP SET, each recorded in the event log with its outcome. */
@ApplicationModule(allowedDependencies = {"snmp", "board"})
package io.github.selvitopu.atmsup.supervisor.control;

import org.springframework.modulith.ApplicationModule;
