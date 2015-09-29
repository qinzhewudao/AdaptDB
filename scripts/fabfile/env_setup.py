from fabric.api import env
from confs import confs

env.use_ssh_config = True
env.conf = None

def setup(mode="server"):
    conf = confs[mode]
    env.conf =  conf
    env.user = conf['USER']
    env.hosts = conf['HOSTS']
    env.roledefs = conf['ROLEDEFS']

def fill_cmd(cmd):
    print env.conf
    for k in env.conf.keys():
        cmd = cmd.replace('$' + k, env.conf[k])
    return cmd
