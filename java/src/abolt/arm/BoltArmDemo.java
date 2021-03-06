package abolt.arm;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.util.*;
import java.io.*;

import lcm.lcm.*;

import april.config.*;
import april.dynamixel.*;
import april.jserial.*;
import april.jmat.*;
import april.jmat.geom.*;
import april.vis.*;
import april.util.*;

import abolt.lcmtypes.*;
import abolt.kinect.*;

public class BoltArmDemo implements LCMSubscriber
{
    // LCM
    LCM lcm = LCM.getSingleton();

    // Arm joints
    ArrayList<Joint> joints;

    // Rendering thread
    RenderThread rt;
    ActionState action = ActionState.UNKNOWN;

    // Simulation thread
    SimulationThread st;

    ExpiringMessageCache<dynamixel_status_list_t> statuses = new ExpiringMessageCache<dynamixel_status_list_t>(0.2, true);
    ExpiringMessageCache<dynamixel_command_list_t> cmds = new ExpiringMessageCache<dynamixel_command_list_t>(0.2, true);
    ExpiringMessageCache<observations_t> observations = new ExpiringMessageCache<observations_t>(2.5, true);

    // Command line flags/options
    GetOpt opts;

    public BoltArmDemo(GetOpt opts_)
    {
        opts = opts_;
        /*if (opts.getString("kconfig") != null) {
            try {
                KUtils.loadCalibrationConfig(new ConfigFile(opts.getString("kconfig")));
            } catch (IOException ioex) {
                System.err.println("ERR: could not load config file");
                ioex.printStackTrace();
            }
        }*/
        joints = BoltArm.initArm();

        // We're going to spoof these if simming, so don't send them
        if (!opts.getBoolean("sim")) {
            lcm.subscribe("ARM_STATUS", this);
        } else {
            st = new SimulationThread();
            st.start();
        }
        lcm.subscribe("ARM_COMMAND", this);
        lcm.subscribe("OBSERVATIONS", this);

        rt = new RenderThread();
        rt.start();
    }

    public void messageReceived(LCM lcm, String channel, LCMDataInputStream ins)
    {
        try {
            messageReceivedEx(lcm, channel, ins);
        } catch (IOException ex) {
            System.out.println("ERR: "+ex);
        }
    }

    public void messageReceivedEx(LCM lcm, String channel, LCMDataInputStream ins) throws IOException
    {
        if (channel.equals("ARM_STATUS")) {
            // If non-simulated, render the arm position from THESE
            dynamixel_status_list_t status = new dynamixel_status_list_t(ins);
            long utime = Long.MAX_VALUE;
            for (dynamixel_status_t s: status.statuses) {
                utime = Math.min(utime, s.utime);
            }
            statuses.put(status, utime);
        } else if (channel.equals("ARM_COMMAND")) {
            // If simulated, use these to render the arm position
            dynamixel_command_list_t cmdl = new dynamixel_command_list_t(ins);
            long utime = Long.MAX_VALUE;
            for (dynamixel_command_t c: cmdl.commands) {
                utime = Math.min(utime, c.utime);
            }
            cmds.put(cmdl, utime);
        } else if (channel.equals("OBSERVATIONS")) {
            // Place observations on the map
            observations_t obs = new observations_t(ins);
            observations.put(obs, obs.utime);
        }
    }

    static enum ActionState
    {
        POINT, GRAB, DROP, SWEEP, RESET, UNKNOWN
    }

    class RenderThread extends Thread
    {
        int fps = 2;

        VisWorld vw;
        VisLayer vl;
        VisCanvas vc;

        double[] xyz = null;

        public RenderThread()
        {
            JFrame jf = new JFrame("Bolt Arm Simulation Demo");
            jf.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            jf.setLayout(new BorderLayout());
            jf.setSize(800, 600);

            vw = new VisWorld();
            vl = new VisLayer(vw);
            vc = new VisCanvas(vl);
            jf.add(vc, BorderLayout.CENTER);

            // ParameterGUI
            ParameterGUI pg = new ParameterGUI();
            pg.addButtons("reset", "Reset");
            jf.add(pg, BorderLayout.SOUTH);

            pg.addListener(new ParameterListener() {
                public void parameterChanged(ParameterGUI pg, String name) {
                    if (name.equals("reset")) {
                        lcm.publish("ROBOT_COMMAND", getRobotCommand(null, ActionState.RESET));
                    }
                }
            });

            // Grid
            VzGrid.addGrid(vw);

            // Default zoom
            vl.cameraManager.fit2D(new double[] {-1,-1}, new double[] {1,1}, true);

            // Event handler
            vl.addEventHandler(new GoalEventHandler());

            jf.setVisible(true);
        }

        public void run()
        {
            while (true) {
                // Render Arm
                {
                    dynamixel_status_list_t dsl = statuses.get();
                    dynamixel_command_list_t dcl = cmds.get();
                    if (opts.getBoolean("sim") && dcl != null) {
                        for (int i = 0; i < dcl.len; i++) {
                            joints.get(i).set(dcl.commands[i].position_radians);
                        }
                    } else if (dsl != null) {
                        for (int i = 0; i < dsl.len; i++) {
                            joints.get(i).set(dsl.statuses[i].position_radians);
                        }
                    }

                    VisWorld.Buffer vb = vw.getBuffer("arm");
                    vb.addBack(new VisChain(LinAlg.rotateZ(-Math.PI/2),
                                            new VzTriangle(0.08, 0.08, 0.08,
                                                           new VzMesh.Style(Color.green))));
                    vb.addBack(new VisChain(LinAlg.translate(0,0,BoltArm.baseHeight/2),
                                            new VzBox(0.04, 0.04, BoltArm.baseHeight,
                                                      new VzMesh.Style(Color.black))));

                    double[][] xform = LinAlg.translate(0,0,BoltArm.baseHeight);
                    for (Joint j: joints) {
                        LinAlg.timesEquals(xform, j.getRotation());
                        vb.addBack(new VisChain(LinAlg.copy(xform),
                                                j.getVis()));
                        LinAlg.timesEquals(xform, j.getTranslation());
                    }
                    vb.swap();
                }

                // Draw current goal
                {
                    VisWorld.Buffer vb = vw.getBuffer("goal");
                    Color color;
                    switch (action) {
                        case POINT:
                            color = new Color(0xff6699);
                            break;
                        case GRAB:
                            color = new Color(0x00cc00);
                            break;
                        case DROP:
                            color = new Color(0x005500);
                            break;
                        case SWEEP:
                            color = new Color(0x00ffff);
                            break;
                        case RESET:
                            color = new Color(0xff0000);
                            break;
                        default:
                            color = new Color(0xffffff);
                            break;
                    }
                    if (xyz != null) {
                        vb.addBack(new VisChain(LinAlg.translate(xyz),
                                                new VzCircle(0.015, new VzMesh.Style(color))));
                    }
                    vb.swap();
                }

                // Draw the currently observed objects w/IDs
                {
                    VisWorld.Buffer vb = vw.getBuffer("observations");
                    observations_t obs = observations.get();
                    if (obs != null) {
                        for (object_data_t od : obs.observations) {
                            Color color = Color.cyan;
                            for (categorized_data_t cat_data: od.cat_dat) {
                                if (cat_data.cat.cat != category_t.CAT_COLOR)
                                    continue;
                                String label = cat_data.label[0];
                                if (label.contains("red")) {
                                    color = Color.red;
                                } else if (label.contains("orange")) {
                                    color = Color.orange;
                                } else if (label.contains("yellow")) {
                                    color = Color.yellow;
                                } else if (label.contains("green")) {
                                    color = Color.green;
                                } else if (label.contains("blue")) {
                                    color = Color.blue;
                                } else if (label.contains("purple")) {
                                    color = Color.magenta;
                                } else if (label.contains("black")) {
                                    color = Color.black;
                                }
                            }
                            if (color.equals(Color.black))
                                continue;
                            Formatter f = new Formatter();
                            f.format("ID: %d", od.id);
                            double[] obj_xyz = LinAlg.resize(od.pos, 3);
                            vb.addBack(new VisChain(LinAlg.translate(obj_xyz),
                                                    LinAlg.scale(0.02),
                                                    new VzSphere(new VzMesh.Style(color))));
                            vb.addBack(new VisChain(LinAlg.translate(obj_xyz),
                                                    LinAlg.scale(0.002),
                                                    new VzText(f.toString())));

                        }
                    vb.swap();
                    }
                }

                TimeUtil.sleep(1000/fps);
            }
        }

        /** Set the arm goal point */
        public void setGoal(double[] goal)
        {
            xyz = LinAlg.copy(goal);
        }
    }

    class GoalEventHandler extends VisEventAdapter
    {
        public int getDispatchOrder()
        {
            return 0;
        }

        public boolean mousePressed(VisCanvas vc, VisLayer vl, VisCanvas.RenderInfo rinfo, GRay3D ray, MouseEvent e)
        {
            double[] xyz = ray.intersectPlaneXY();
            int mods = e.getModifiersEx();
            boolean shift = (mods & MouseEvent.SHIFT_DOWN_MASK) > 0;
            boolean ctrl = (mods & MouseEvent.CTRL_DOWN_MASK) > 0;
            observations_t obs = observations.get();
            double minDist = Double.MAX_VALUE;
            int id = 0;
            double[] objPos = null;
            if (obs != null) {
                for (object_data_t obj_dat : obs.observations) {
                    double[] pos = LinAlg.resize(obj_dat.pos, 3);
                    double mag = LinAlg.distance(pos, xyz);
                    if (mag < minDist) {
                        minDist = mag;
                        id = obj_dat.id;
                        objPos = pos;
                    }
                }
            }

            if (shift && !ctrl) {
                if (minDist < 0.05) {
                    lcm.publish("ROBOT_COMMAND", getRobotCommand(id, ActionState.POINT));
                    rt.setGoal(objPos);
                } else {
                    lcm.publish("ROBOT_COMMAND", getRobotCommand(xyz, ActionState.POINT));
                    rt.setGoal(xyz);
                }
                return true;
            } else if (!shift && ctrl) {
                lcm.publish("ROBOT_COMMAND", getRobotCommand(xyz, ActionState.DROP));
                rt.setGoal(xyz);
                return true;
            } else if (shift && ctrl) {
                if (minDist != Double.MAX_VALUE) {
                    lcm.publish("ROBOT_COMMAND", getRobotCommand(id, ActionState.GRAB));
                    rt.setGoal(objPos);
                    return true;
                }
            }

            return false;
        }

        public boolean mouseDragged(VisCanvas vc, VisLayer vl, VisCanvas.RenderInfo rinfo, GRay3D ray, MouseEvent e)
        {
            double[] xyz = ray.intersectPlaneXY();
            int mods = e.getModifiersEx();
            boolean shift = (mods & MouseEvent.SHIFT_DOWN_MASK) > 0;
            boolean ctrl = (mods & MouseEvent.CTRL_DOWN_MASK) > 0;
            /*if (shift && !ctrl) {
                lcm.publish("ROBOT_COMMAND", getRobotCommand(xyz, ActionState.POINT));
                rt.setGoal(xyz);
                return true;
            } else if (!shift && ctrl) {
                lcm.publish("ROBOT_COMMAND", getRobotCommand(xyz, ActionState.DROP));
                rt.setGoal(xyz);
                return true;
            } else if (shift && ctrl) {
                lcm.publish("ROBOT_COMMAND", getRobotCommand(xyz, ActionState.GRAB));
                rt.setGoal(xyz);
                return true;
            }*/

            return false;
        }
    }

    class SimulationThread extends Thread
    {
        int Hz = 15;

        public void run()
        {
            while (true) {
                TimeUtil.sleep(1000/Hz);

                long utime = TimeUtil.utime();
                dynamixel_status_list_t dsl = new dynamixel_status_list_t();
                dsl.len = joints.size();
                dsl.statuses = new dynamixel_status_t[joints.size()];
                for (int i = 0; i < joints.size(); i++) {
                    dynamixel_status_t status = new dynamixel_status_t();
                    status.utime = utime;

                    Joint j = joints.get(i);
                    if (j instanceof RevoluteJoint) {
                        RevoluteJoint rj = (RevoluteJoint)j;
                        status.position_radians = rj.getAngle();
                    } else if (j instanceof HandJoint) {
                        HandJoint hj = (HandJoint)j;
                        status.position_radians = hj.getAngle();
                    }

                    dsl.statuses[i] = status;
                }

                lcm.publish("ARM_STATUS", dsl);
            }
        }
    }

    private robot_command_t getRobotCommand(int id, ActionState state)
    {
        action = state;
        robot_command_t cmd = new robot_command_t();
        cmd.utime = TimeUtil.utime();
        cmd.updateDest = false;
        cmd.dest = new double[6];
        switch (state) {
            case POINT:
                cmd.action = "POINT="+id;
                break;
            case GRAB:
                cmd.action = "GRAB="+id;
                break;
            case SWEEP:
                cmd.action = "SWEEP="+id;
                break;
            case DROP:
                cmd.action = "DROP="+id;
                break;
            default:
                cmd.action = "RESET="+id;
                break;
        }

        return cmd;
    }

    private robot_command_t getRobotCommand(double[] dest, ActionState state)
    {
        action = state;
        robot_command_t cmd = new robot_command_t();
        cmd.utime = TimeUtil.utime();
        cmd.updateDest = (dest != null);
        cmd.dest = (dest == null) ? new double[6] : LinAlg.resize(dest, 6);
        switch (state) {
            case POINT:
                cmd.action = "POINT";
                break;
            case GRAB:
                cmd.action = "GRAB";
                break;
            case SWEEP:
                cmd.action = "SWEEP";
                break;
            case DROP:
                cmd.action = "DROP";
                break;
            default:
                cmd.action = "RESET";
                break;
        }

        return cmd;
    }


    static public void main(String[] args)
    {
        GetOpt opts = new GetOpt();
        opts.addBoolean('s',"sim",false,"Run in simulation mode");
        opts.addBoolean('h',"help",false,"Display this help screen");
        opts.addBoolean('l',"lcm",false,"Emit LCM");
        opts.addString('k',"kconfig",null,"Kinect calibration config file");

        if (!opts.parse(args)) {
            System.err.println("ERR: Option error - "+opts.getReason());
            return;
        }

        if (opts.getBoolean("help")) {
            opts.doHelp();
            return;
        }

        BoltArmDemo bd = new BoltArmDemo(opts);
    }
}
