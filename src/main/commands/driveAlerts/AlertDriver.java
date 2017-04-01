package main.commands.driveAlerts;

import edu.wpi.first.wpilibj.command.CommandGroup;
import edu.wpi.first.wpilibj.command.WaitCommand;
import main.Constants;

public class AlertDriver extends CommandGroup implements Constants {
	public AlertDriver() {// Utilize pulseAlertLight method to simplify this.
		for (int i = 0; i < 10; i++) {
			addSequential(new AlertLightOnForTime(alertOnTime));
			addSequential(new WaitCommand(alertOffTime));
		}
	}
}
